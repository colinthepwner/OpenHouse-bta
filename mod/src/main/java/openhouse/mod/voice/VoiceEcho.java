package openhouse.mod.voice;

final class VoiceEcho {
    static final int N = 1024, B = 512, P = 6, BINS = N / 2 + 1;
    static final int RING = 48000 * 3;
    static final float MU = 0.5F;
    static final float RES_FLOOR = 0.3F;
    static final int ENV = 480, ENV_HIST = 160, MAX_LAG = 60;

    static final long T0 = System.nanoTime();
    static volatile long fake_clock = -1;
    static long clock() { return fake_clock >= 0 ? fake_clock : (System.nanoTime() - T0) * 48000L / 1_000_000_000L; }

    private static final float[] ring = new float[RING];
    private static long ring_hi;
    private static volatile long last_far;
    private static volatile long last_voice;

    static void write(long pos, short[] pcm, float gain) {
        float[] f = new float[pcm.length];
        for (int i = 0; i < f.length; i++) f[i] = pcm[i];
        write(pos, f, gain, true);
    }

    static void write(long pos, float[] pcm, float gain, boolean voice) {
        if (gain <= 0.001F) return;
        synchronized (ring) {
            long now = clock();

            long end = Math.min(pos + pcm.length, now + RING / 2);
            if (end <= pos || end < now - RING / 2) return;

            for (long p = Math.max(ring_hi, end - RING); p < end; p++) ring[(int) (p % RING)] = 0;
            if (end > ring_hi) ring_hi = end;
            int n = (int) (end - pos);
            for (int i = 0; i < n; i++) ring[(int) ((pos + i) % RING)] += pcm[i] * gain;
            if (end > last_far) last_far = end;
            if (voice && end > last_voice) last_voice = end;
        }
    }

    static void read(long pos, float[] out, int off, int n) {
        synchronized (ring) {
            long now = clock();
            for (int i = 0; i < n; i++) {
                long p = pos + i;
                out[off + i] = p < 0 || p >= ring_hi || p < now - RING + 4800 ? 0 : ring[(int) (p % RING)];
            }
        }
    }

    static final int AREA = 48000 * 7 / 10;
    static float far_level(long pos) {
        float top = 0;
        synchronized (ring) {
            long now = clock();
            for (long p0 = pos - AREA; p0 < pos; p0 += 480) {
                double s = 0;
                for (int i = 0; i < 480; i += 2) {
                    long p = p0 + i;
                    float v = p < 0 || p >= ring_hi || p < now - RING + 4800 ? 0 : ring[(int) (p % RING)];
                    s += v * v;
                }
                top = Math.max(top, (float) Math.sqrt(s / 240));
            }
        }
        return top;
    }

    static boolean playing_now() { return clock() - last_voice < 14_400; }

    static boolean far_recent() { return clock() - last_far < 48000; }

    private final float[] cosT = new float[N / 2], sinT = new float[N / 2];
    private final int[] rev = new int[N];
    private final float[][] Wr = new float[P][BINS], Wi = new float[P][BINS];
    private final float[][] Fr = new float[P][BINS], Fi = new float[P][BINS];

    private float sf, sb;
    private int better;
    private final float[][] Xr = new float[P][BINS], Xi = new float[P][BINS];
    private final float[] pw = new float[BINS];
    private final float[] xbuf = new float[N];
    private final float[] fr = new float[N], fi = new float[N];
    private final float[] inq = new float[B * 4], outq = new float[B * 6];
    private int inn, outn;
    private long mic_pos = Long.MIN_VALUE;
    private long inq_pos;
    private int part_rot, bad_run;
    private float res_g = 1;
    float erle = 0;
    volatile boolean echo_now;

    private final float[] env_mic = new float[ENV_HIST], env_far = new float[ENV_HIST];
    private int env_n, env_acc_n;
    private float env_m, env_f;
    private int delay = 4800;
    private int delay_votes, delay_cand = -1;
    private long since_check;

    VoiceEcho() {
        for (int i = 0; i < N / 2; i++) { cosT[i] = (float) Math.cos(-2 * Math.PI * i / N); sinT[i] = (float) Math.sin(-2 * Math.PI * i / N); }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < B; i++) outq[outn++] = 0;
    }

    long frame_start(long read_at, int n) {
        long start = read_at - n;
        return mic_pos == Long.MIN_VALUE || Math.abs(start - mic_pos) > 480 ? start : mic_pos;
    }

    void process(short[] f, long read_at) {
        long start = read_at - f.length;

        if (mic_pos == Long.MIN_VALUE || Math.abs(start - mic_pos) > 480) { mic_pos = start; inq_pos = start - inn; }
        for (short s : f) inq[inn++] = s;
        while (inn >= B) {
            block(inq_pos);
            System.arraycopy(inq, B, inq, 0, inn - B);
            inn -= B;
            inq_pos += B;
        }
        mic_pos += f.length;
        for (int i = 0; i < f.length; i++) f[i] = (short) Math.max(-32768, Math.min(32767, outq[i]));
        System.arraycopy(outq, f.length, outq, 0, outn - f.length);
        outn -= f.length;
    }

    private final float[] far = new float[B], e = new float[B], y = new float[B], yb = new float[B], eb = new float[B];
    private void block(long pos) {
        read(pos - delay, far, 0, B);
        envelopes(pos);

        System.arraycopy(xbuf, B, xbuf, 0, B);
        System.arraycopy(far, 0, xbuf, B, B);
        float[] tr = Xr[P - 1], ti = Xi[P - 1];
        for (int p = P - 1; p > 0; p--) { Xr[p] = Xr[p - 1]; Xi[p] = Xi[p - 1]; }
        Xr[0] = tr; Xi[0] = ti;
        for (int i = 0; i < N; i++) { fr[i] = xbuf[i]; fi[i] = 0; }
        fft(fr, fi);
        for (int k = 0; k < BINS; k++) { Xr[0][k] = fr[k]; Xi[0][k] = fi[k]; }

        for (int k = 0; k < BINS; k++) {
            float s = 0;
            for (int p = 0; p < P; p++) s += Xr[p][k] * Xr[p][k] + Xi[p][k] * Xi[p][k];
            pw[k] = 0.7F * pw[k] + 0.3F * s;
        }

        estimate(Wr, Wi, yb);
        estimate(Fr, Fi, y);
        float em = 0, ee = 0, ey = 0, ef = 0, eeb = 0;
        for (int i = 0; i < B; i++) {
            float m = inq[i];
            e[i] = m - y[i];
            eb[i] = m - yb[i];
            em += m * m; ee += e[i] * e[i]; ey += y[i] * y[i]; ef += far[i] * far[i]; eeb += eb[i] * eb[i];
        }
        boolean far_on = ef > B * 300F * 300F;
        if (far_on) {
            sf = 0.6F * sf + 0.4F * ee;
            sb = 0.6F * sb + 0.4F * eeb;
            if (sb < 0.7F * sf) { if (++better >= 3) { copy(Wr, Wi, Fr, Fi); better = 0; sf = sb; } } else better = 0;
            if (sb > 4 * sf + 1e5F && sb > em) { copy(Fr, Fi, Wr, Wi); sb = sf; }
        }

        if (ee > em * 1.5F + 1e5F) {
            if (++bad_run > 25) { reset(); bad_run = 0; }
            for (int i = 0; i < B; i++) e[i] = inq[i];
            ee = em; ey = 0;
        } else bad_run = 0;
        if (eeb > em * 4 + 1e5F) for (int i = 0; i < B; i++) eb[i] = 0;

        float mu = MU;
        if (erle > 6 && em > 8 * ey && far_on) mu *= 0.25F;
        if (far_on) {
            for (int i = 0; i < B; i++) { fr[i] = 0; fi[i] = 0; fr[B + i] = eb[i]; fi[B + i] = 0; }
            fft(fr, fi);
            for (int p = 0; p < P; p++) for (int k = 0; k < BINS; k++) {
                float g = mu / (pw[k] + 1e3F);
                float a = Xr[p][k], b = -Xi[p][k];
                Wr[p][k] += g * (a * fr[k] - b * fi[k]);
                Wi[p][k] += g * (a * fi[k] + b * fr[k]);
            }
            constrain(part_rot);
            part_rot = (part_rot + 1) % P;
        }

        float want = 1;
        if (far_on && ey > 0 && erle > 3 && ee < ey * 2) want = Math.max(RES_FLOOR, Math.min(1, 1 - 1.2F * ey / Math.max(1, ee + ey)));
        res_g += (want - res_g) * (want < res_g ? 0.3F : 0.25F);
        echo_now = far_on && ee < ey * 3;
        if (em > 0 && far_on) erle = 0.95F * erle + 0.05F * (float) (10 * Math.log10((em + 1) / (ee + 1)));
        for (int i = 0; i < B; i++) outq[outn++] = e[i] * res_g;
    }

    private void constrain(int p) {
        for (int k = 0; k < BINS; k++) { fr[k] = Wr[p][k]; fi[k] = Wi[p][k]; }
        mirror(fr, fi);
        ifft(fr, fi);
        for (int i = B; i < N; i++) fr[i] = 0;
        for (int i = 0; i < N; i++) fi[i] = 0;
        fft(fr, fi);
        for (int k = 0; k < BINS; k++) { Wr[p][k] = fr[k]; Wi[p][k] = fi[k]; }
    }

    private void reset() {
        for (int p = 0; p < P; p++) { java.util.Arrays.fill(Wr[p], 0); java.util.Arrays.fill(Wi[p], 0); java.util.Arrays.fill(Fr[p], 0); java.util.Arrays.fill(Fi[p], 0); }
        res_g = 1; sf = sb = 0; better = 0;
    }

    private void estimate(float[][] wr, float[][] wi, float[] out) {
        for (int i = 0; i < N; i++) { fr[i] = 0; fi[i] = 0; }
        for (int p = 0; p < P; p++) for (int k = 0; k < BINS; k++) {
            float a = Xr[p][k], b = Xi[p][k], c = wr[p][k], d = wi[p][k];
            fr[k] += a * c - b * d; fi[k] += a * d + b * c;
        }
        mirror(fr, fi);
        ifft(fr, fi);
        System.arraycopy(fr, B, out, 0, B);
    }

    private static void copy(float[][] ar, float[][] ai, float[][] br, float[][] bi) {
        for (int p = 0; p < P; p++) { System.arraycopy(ar[p], 0, br[p], 0, BINS); System.arraycopy(ai[p], 0, bi[p], 0, BINS); }
    }

    private final float[] raw_far = new float[B];
    private void envelopes(long pos) {

        read(pos, raw_far, 0, B);
        for (int i = 0; i < B; i++) { env_m += inq[i] * inq[i]; env_f += raw_far[i] * raw_far[i]; env_acc_n++;
            if (env_acc_n == ENV) {
                System.arraycopy(env_mic, 1, env_mic, 0, ENV_HIST - 1); env_mic[ENV_HIST - 1] = (float) Math.log10(env_m + 1);
                System.arraycopy(env_far, 1, env_far, 0, ENV_HIST - 1); env_far[ENV_HIST - 1] = (float) Math.log10(env_f + 1);
                env_m = env_f = 0; env_acc_n = 0; if (env_n < ENV_HIST) env_n++;
            }
        }
        since_check += B;
        if (since_check < 24000 || env_n < ENV_HIST) return;
        since_check = 0;

        float fmin = Float.MAX_VALUE, fmax = -Float.MAX_VALUE;
        for (float v : env_far) { fmin = Math.min(fmin, v); fmax = Math.max(fmax, v); }
        if (fmax - fmin < 2) return;
        int best = -1; float bc = 0.6F;
        int len = ENV_HIST - MAX_LAG;
        for (int lag = 0; lag <= MAX_LAG; lag++) {

            double sm = 0, sf = 0, smm = 0, sff = 0, smf = 0;
            for (int t = MAX_LAG; t < ENV_HIST; t++) {
                double m = env_mic[t], fv = env_far[t - lag];
                sm += m; sf += fv; smm += m * m; sff += fv * fv; smf += m * fv;
            }
            double cov = smf - sm * sf / len, vm = smm - sm * sm / len, vf = sff - sf * sf / len;
            if (vm <= 1e-6 || vf <= 1e-6) continue;
            float c = (float) (cov / Math.sqrt(vm * vf));
            if (c > bc) { bc = c; best = lag; }
        }
        if (best < 0) return;

        if (erle > 6) return;

        int d = Math.max(0, best * ENV - 960);
        if (Math.abs(d - delay) <= ENV) { delay_votes = 0; return; }
        if (d == delay_cand) delay_votes++; else { delay_cand = d; delay_votes = 1; }
        if (delay_votes >= 2) { delay = d; delay_votes = 0; reset(); }
    }

    int delay_ms() { return delay / 48; }

    private void fft(float[] re, float[] im) { fft(re, im, false); }
    private void ifft(float[] re, float[] im) { fft(re, im, true); float s = 1F / N; for (int i = 0; i < N; i++) { re[i] *= s; im[i] *= s; } }
    private void fft(float[] re, float[] im, boolean inv) {
        for (int i = 0; i < N; i++) { int j = rev[i]; if (j > i) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1, step = N / len;
            for (int i = 0; i < N; i += len) for (int j = 0; j < half; j++) {
                float wr = cosT[j * step], wi = inv ? -sinT[j * step] : sinT[j * step];
                int a = i + j, b = a + half;
                float xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
            }
        }
    }

    private static void mirror(float[] re, float[] im) {
        for (int k = 1; k < N / 2; k++) { re[N - k] = re[k]; im[N - k] = -im[k]; }
        im[0] = 0; im[N / 2] = 0;
    }
}
