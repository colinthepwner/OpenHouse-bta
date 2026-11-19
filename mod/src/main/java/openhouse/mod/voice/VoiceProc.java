package openhouse.mod.voice;

import de.maxhenkel.rnnoise4j.Denoiser;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import java.util.ArrayDeque;

final class VoiceProc {
    static final float VAD_START = 0.8F, VAD_HOLD = 0.3F;
    static final float MIN_RMS = 130;
    static final int HANG = 15, PREROLL = 4;

    static final int OPEN_FRAMES = 3;

    static final float OVER_ECHO = 3, COUPLE_START = 0.5F, COUPLE_MIN = 0.01F, COUPLE_MAX = 4;
    static final float TARGET_RMS = 3200, MIN_GAIN = 0.6F, MAX_GAIN = 4, GAIN_EASE = 0.04F;
    static final float KNEE = 24000;
    static final int BITRATE = 32000;
    static final float GATE_OVER_FLOOR = 3;

    interface Out { void send(byte[] opus, int loud); }

    interface Tap { void frame(short[] f, boolean talking); }
    volatile Tap tap;
    float last_prob;

    private static volatile boolean no_rnnoise;
    private Denoiser dn;
    private final OpusEncoder enc;
    private final short[] half = new short[Voice.FRAME / 2];
    private final byte[] pkt = new byte[1275];
    private final ArrayDeque<short[]> pre = new ArrayDeque<>();
    volatile boolean clean = true;
    private boolean talking;
    private int hang;
    private float gain = 1, floor = 300;
    volatile float level;
    private int loud = 1;
    volatile float far_lvl;
    private float couple = COUPLE_START, room = 300;
    private int open_run;
    String gate() { return String.format("%.2f/%d/%d", couple, Math.round(far_lvl), Math.round(room)); }

    VoiceProc() throws Exception {
        enc = new OpusEncoder(Voice.RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        enc.setBitrate(BITRATE);
        enc.setComplexity(5);
        enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        if (!no_rnnoise) {
            try {
                dn = new Denoiser();
                if (dn.getFrameSize() != Voice.FRAME / 2) { dn.close(); dn = null; throw new IllegalStateException("rnnoise frame " + Voice.FRAME / 2); }
            } catch (Throwable e) {
                no_rnnoise = true;
                dn = null;
                openhouse.mod.OpenHouseMod.LOG.info("[voice] noise removal unavailable ({}), using a plain gate", e.toString());
            }
        }
    }

    static boolean denoise_broken() { return no_rnnoise; }
    boolean talking() { return talking; }

    void process(short[] f, Out out, boolean echo, Boolean ptt) throws Exception {
        float prob = 0;

        float raw = rms(f);
        if (dn != null) {
            int n = half.length;
            for (int part = 0; part < 2; part++) {
                System.arraycopy(f, part * n, half, 0, n);
                prob = Math.max(prob, clean ? dn.denoiseInPlace(half) : dn.getSpeechProbability(half));
                if (clean) System.arraycopy(half, 0, f, part * n, n);
            }
        }
        last_prob = prob;
        float rms = rms(f);
        if (dn == null) {
            floor = rms < floor ? rms : floor + (rms - floor) * 0.002F;
            floor = Math.max(30, floor);
            prob = rms > floor * GATE_OVER_FLOOR ? 1 : 0;
        }
        loud = rms < 1 ? 1 : Math.max(1, Math.min(255, Math.round(((float) (20 * Math.log10(rms / 32768)) + 60) * 255 / 60)));

        room = rms < room ? Math.max(30, rms) : room + (rms - room) * 0.002F;
        float far = far_lvl;

        if (far > 200 && !talking && prob < 0.5F) {
            float r = Math.max(COUPLE_MIN, Math.min(COUPLE_MAX, raw / far));
            couple += (r - couple) * (r > couple ? 0.1F : 0.02F);
        }
        boolean voiced;

        if (talking) voiced = rms >= MIN_RMS && prob >= VAD_HOLD && (far <= 200 || raw >= OVER_ECHO * 0.5F * couple * far);
        else {
            boolean go = rms >= MIN_RMS && prob >= VAD_START && rms >= room * 3;
            if (echo) go = go && rms >= MIN_RMS * 1.5F && prob >= 0.9F;
            if (far > 200) go = go && raw >= OVER_ECHO * couple * far;
            open_run = go ? open_run + 1 : 0;
            voiced = open_run >= OPEN_FRAMES;
        }
        if (ptt != null) voiced = ptt;
        if (voiced) {
            hang = ptt != null ? 3 : HANG;
            float want = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_RMS / Math.max(1, rms)));
            gain += (want - gain) * GAIN_EASE;
        } else if (hang > 0) hang--;
        amp(f, gain);
        level = Math.min(1, rms(f) / 12000);
        Tap tp = tap;
        if (tp != null) tp.frame(f, hang > 0);
        if (hang > 0) {
            if (!talking) {
                talking = true;
                for (short[] e : pre) encode(e, out);
                pre.clear();
            }
            encode(f, out);
        } else {
            end(out);
            pre.addLast(f.clone());
            while (pre.size() > PREROLL) pre.removeFirst();
        }
    }

    void end(Out out) {
        if (!talking) return;
        talking = false;
        hang = 0;
        out.send(new byte[0], 0);
    }

    void close() {
        if (dn != null) try { dn.close(); } catch (Throwable ignored) { }
        dn = null;
    }

    private void encode(short[] f, Out out) throws Exception {
        int n = enc.encode(f, 0, Voice.FRAME, pkt, 0, pkt.length);
        if (n <= 0) return;
        byte[] c = new byte[n];
        System.arraycopy(pkt, 0, c, 0, n);
        out.send(c, loud);
    }

    private static void amp(short[] f, float g) {
        for (int i = 0; i < f.length; i++) {
            float v = f[i] * g, a = Math.abs(v);
            if (a > KNEE) {
                float over = (a - KNEE) / (32767 - KNEE);
                v = Math.signum(v) * (KNEE + (32767 - KNEE) * (over / (1 + over)));
            }
            f[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
    }

    static float rms(short[] f) {
        double s = 0;
        for (short x : f) s += (double) x * x;
        return (float) Math.sqrt(s / f.length);
    }
}
