package openhouse.mod.voice;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public final class HurtClip {
    private HurtClip() { }

    static final int SOUND_MAX = 40, GAP = 6, CUT_FADE = 10;
    static final int TAIL = 8, PRE = 2;
    static final int GATE_SPAN = 150;
    static final float LEVEL = 3200, PEAK = 24000;
    static volatile byte[] clip;
    static volatile int version;

    private static File file() { return new File(openhouse.mod.OpenHouseMod.dataDir(), "hurt-sound.clip"); }

    static void load() {
        try {
            File f = file();
            if (!f.isFile()) return;
            byte[] d = Files.readAllBytes(f.toPath());
            if (VoiceWire.clip_frames(d, 0) != null) { clip = d; version++; }
        } catch (Throwable ignored) { }
    }

    public static boolean has() { return clip != null; }
    public static int clip_size() { byte[] c = clip; return c == null ? 0 : c.length; }
    public static int theirs_count() { return theirs.size(); }

    private static void set(byte[] c) {
        clip = c;
        version++;
        try { File d = openhouse.mod.OpenHouseMod.dataDir(); d.mkdirs(); Files.write(file().toPath(), c); } catch (Throwable ignored) { }
    }

    private static volatile Rec rec;
    public static volatile boolean loading;
    public static boolean recording() { return rec != null; }
    public static boolean busy() { return rec != null || loading; }
    public static void start() { if (!busy()) { rec = new Rec(); Thread t = new Thread(rec, "OpenHouse hurt sound"); t.setDaemon(true); t.start(); } }
    public static void stop() { Rec r = rec; if (r != null) r.stop = true; }
    public static void until_quiet() { Rec r = rec; if (r != null) r.auto = true; }
    public static volatile String said;

    static final class Rec implements Runnable {
        volatile boolean stop, auto;
        public void run() {
            List<short[]> frames = new ArrayList<>();
            TargetDataLine l = null;
            try {
                VoiceProc p = new VoiceProc();
                p.clean = VoiceCfg.denoise;
                final boolean[] talk = new boolean[1];
                p.tap = (f, t) -> { frames.add(f.clone()); talk[0] = t; };
                String file = System.getProperty("openhouse.voice.file");
                AudioInputStream fin = null;
                if (file != null) fin = AudioSystem.getAudioInputStream(new File(file));
                else {
                    l = Mics.open(VoiceCfg.device(), 960 * 2 * 6);
                    if (l == null) { said = "gui.openhouse.hurt.nomic"; return; }
                    l.start();
                }
                long next = System.nanoTime();
                byte[] raw = new byte[960 * 2];
                short[] f = new short[960];
                int spoke = -1, quiet = 0;
                long until = System.currentTimeMillis() + 6000;
                while (!stop && System.currentTimeMillis() < until && frames.size() < 400) {
                    int got = 0;
                    if (fin != null) {
                        while (got < raw.length) {
                            int n = fin.read(raw, got, raw.length - got);
                            if (n <= 0) { fin.close(); fin = AudioSystem.getAudioInputStream(new File(file)); continue; }
                            got += n;
                        }
                        next += 20_000_000L;
                        long w = next - System.nanoTime();
                        if (w > 0) Thread.sleep(w / 1_000_000L, (int) (w % 1_000_000L));
                    } else while (got < raw.length) { int n = l.read(raw, got, raw.length - got); if (n <= 0) break; got += n; }
                    if (got < raw.length) break;
                    for (int i = 0; i < f.length; i++) f[i] = (short) ((raw[i * 2] & 0xFF) | raw[i * 2 + 1] << 8);
                    p.process(f, (b, lo) -> { }, false, null);
                    if (talk[0]) { if (spoke < 0) spoke = frames.size(); quiet = 0; } else if (spoke >= 0) quiet++;

                    if (auto && spoke >= 0 && quiet >= 10) break;
                    if (spoke >= 0 && frames.size() - spoke > SOUND_MAX + 15) break;
                }
                p.close();
                if (fin != null) fin.close();
            } catch (Throwable e) {
                said = "gui.openhouse.hurt.failed";
            } finally {
                if (l != null) try { l.stop(); l.close(); } catch (Throwable ignored) { }
                rec = null;
            }
            try { short[] pcm = trim(frames); if (pcm != null) keep(pcm); } catch (Throwable e) { said = "gui.openhouse.hurt.failed"; }
        }
    }

    static final long FILE_MAX_BYTES = 20L << 20;
    static final int FILE_MAX_FRAMES = 50 * 20;
    static final float GATE_CLIPPED = 0.005F, GATE_CREST = 2.5F, GATE_SPEECH = 0.45F;

    static final float GATE_FLUX = 3.0F, FLUX_LOW = 2.5F, GATE_STILL = 0.3F;
    static final int SNIP = 30;
    static final float SNIP_RMS = 250, SNIP_PEAK = 2000;

    public static void upload() {
        if (busy()) return;
        loading = true;
        said = null;
        Thread t = new Thread(() -> {
            try {
                File f = FilePick.audio();
                if (f != null) from_file(f);
            } catch (Throwable e) {
                said = "gui.openhouse.hurt.badfile";
                openhouse.mod.OpenHouseMod.LOG.info("[voice] hurt sound file: {}", e.toString());
            } finally { loading = false; }
        }, "OpenHouse hurt sound file");
        t.setDaemon(true);
        t.start();
    }

    public static void from_file(File f) throws Exception {
        if (!f.isFile() || f.length() > FILE_MAX_BYTES) { said = "gui.openhouse.hurt.toobig"; return; }
        short[] all = decode_file(f);
        if (all == null || all.length < Voice.FRAME) { said = "gui.openhouse.hurt.badfile"; return; }
        int n = Math.min(all.length / Voice.FRAME, FILE_MAX_FRAMES);

        float[] rr = new float[n];
        float top = 0;
        for (int i = 0; i < n; i++) { rr[i] = rms(all, i * Voice.FRAME, Voice.FRAME); top = Math.max(top, rr[i]); }
        float th = Math.max(VoiceProc.MIN_RMS * 1.5F, top * 0.08F);
        int a = -1, b = -1;
        for (int i = 0; i < n; i++) if (rr[i] > th) { if (a < 0) a = i; b = i; }
        if (a < 0) { said = "gui.openhouse.hurt.nothing"; return; }
        int span = b - a + 1, clipped = 0, peak = 0;
        double e = 0;
        for (int s = a * Voice.FRAME; s < (b + 1) * Voice.FRAME; s++) { int v = Math.abs(all[s]); if (v >= 32000) clipped++; if (v > peak) peak = v; e += (double) all[s] * all[s]; }
        float loud = (float) Math.sqrt(e / (span * Voice.FRAME)), crest = peak / Math.max(1, loud), clip_frac = clipped / (float) (span * Voice.FRAME);

        VoiceProc p = new VoiceProc();
        p.clean = VoiceCfg.denoise;
        List<short[]> frames = new ArrayList<>();
        p.tap = (fr, t) -> frames.add(fr.clone());
        int voiced = 0, judged = 0;
        try {
            for (int i = 0; i < n; i++) {
                short[] fr = new short[Voice.FRAME];
                System.arraycopy(all, i * Voice.FRAME, fr, 0, Voice.FRAME);
                p.process(fr, (x, y) -> { }, false, null);
                if (i >= a && i <= b && rr[i] > th) { judged++; if (p.last_prob >= 0.5F) voiced++; }
            }
        } finally { p.close(); }
        float speech = judged == 0 ? 0 : voiced / (float) judged;
        float[] fx = flux(all, rr, th, a, b);
        boolean ok = span <= GATE_SPAN && clip_frac < GATE_CLIPPED && crest >= GATE_CREST && (VoiceProc.denoise_broken() || speech >= GATE_SPEECH)
                && fx[0] >= GATE_FLUX && fx[1] < GATE_STILL;
        openhouse.mod.OpenHouseMod.LOG.info("[voice] hurt sound file {}: {} s, sound {} s, clipped {}%, crest {}, speech {}%, flux {} still {}% -> {}", f.getName(),
                String.format("%.1f", all.length / 48000F), String.format("%.2f", span / 50F), String.format("%.2f", clip_frac * 100), String.format("%.1f", crest), Math.round(speech * 100),
                String.format("%.2f", fx[0]), Math.round(fx[1] * 100), ok ? "kept" : "quiet snip");
        if (ok) { short[] pcm = trim(frames); if (pcm != null) keep(pcm); return; }
        keep(snip(frames));
        if (said == null) said = "gui.openhouse.hurt.quiet";
    }

    static final int FB0 = 6, FB1 = 100;
    private static float[][] dft_c, dft_s;
    static float[] flux(short[] all, float[] rr, float th, int a, int b) {
        int F = Voice.FRAME, nb = FB1 - FB0;
        if (dft_c == null) {
            float[][] c = new float[nb][F], sn = new float[nb][F];
            for (int k = 0; k < nb; k++) for (int t = 0; t < F; t++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * t / (F - 1)), ph = 2 * Math.PI * (k + FB0) * t / F;
                c[k][t] = (float) (w * Math.cos(ph)); sn[k][t] = (float) (w * Math.sin(ph));
            }
            dft_c = c; dft_s = sn;
        }
        float[] prev = null, cur = new float[nb];
        List<Float> fl = new ArrayList<>();
        for (int i = a; i <= b; i++) {
            if (rr[i] <= th) { prev = null; continue; }
            int o = i * F;
            float floor = rr[i] * 0.5F + 1;
            for (int k = 0; k < nb; k++) {
                float re = 0, im = 0;
                float[] ck = dft_c[k], sk = dft_s[k];
                for (int t = 0; t < F; t++) { float v = all[o + t]; re += v * ck[t]; im += v * sk[t]; }
                cur[k] = (float) (20 * Math.log10(Math.sqrt(re * re + im * im) + floor));
            }
            if (prev != null) {
                float mean = 0;
                for (int k = 0; k < nb; k++) mean += cur[k] - prev[k];
                mean /= nb;
                float d = 0;
                for (int k = 0; k < nb; k++) d += Math.abs(cur[k] - prev[k] - mean);
                fl.add(d / nb);
            } else prev = new float[nb];
            System.arraycopy(cur, 0, prev, 0, nb);
        }
        if (fl.isEmpty()) return new float[] { 0, 1 };
        java.util.Collections.sort(fl);
        int still = 0;
        for (float v : fl) if (v < FLUX_LOW) still++;
        return new float[] { fl.get(fl.size() / 2), still / (float) fl.size() };
    }

    static short[] snip(List<short[]> frames) {
        int n = frames.size(), best = 0;
        float bm = -1;
        for (int i = 0; i < n; i++) { float r = VoiceProc.rms(frames.get(i)); if (r > bm) { bm = r; best = i; } }
        int len = Math.min(SNIP, n), a = Math.max(0, Math.min(n - len, best - len / 2));
        short[] pcm = new short[len * Voice.FRAME];
        for (int i = 0; i < len; i++) System.arraycopy(frames.get(a + i), 0, pcm, i * Voice.FRAME, Voice.FRAME);
        int pk = 1;
        for (short s : pcm) pk = Math.max(pk, Math.abs(s));
        float r = Math.max(1, rms(pcm, 0, pcm.length));
        float g = Math.min(1, Math.min(SNIP_RMS / r, SNIP_PEAK / pk));
        int fin = 48000 * 15 / 100, fout = 48000 * 30 / 100;
        for (int s = 0; s < pcm.length; s++) {
            float k = g;
            if (s < fin) k *= 0.5F - 0.5F * (float) Math.cos(Math.PI * s / fin);
            int left = pcm.length - 1 - s;
            if (left < fout) k *= 0.5F - 0.5F * (float) Math.cos(Math.PI * left / fout);
            pcm[s] = (short) (pcm[s] * k);
        }
        return pcm;
    }

    static short[] decode_file(File f) throws Exception {
        String nm = f.getName().toLowerCase(java.util.Locale.ROOT);
        byte[] data;
        AudioFormat af;
        if (nm.endsWith(".ogg")) {
            Class<?> c = Class.forName("paulscode.sound.codecs.CodecJOrbis", true, HurtClip.class.getClassLoader());
            Object codec = c.getConstructor().newInstance();
            if (!(Boolean) c.getMethod("initialize", java.net.URL.class).invoke(codec, f.toURI().toURL())) return null;
            Object buf = c.getMethod("readAll").invoke(codec);
            try { c.getMethod("cleanup").invoke(codec); } catch (Throwable ignored) { }
            if (buf == null) return null;
            data = (byte[]) buf.getClass().getField("audioData").get(buf);
            af = (AudioFormat) buf.getClass().getField("audioFormat").get(buf);
        } else {
            try (AudioInputStream in0 = AudioSystem.getAudioInputStream(f)) {
                AudioFormat src = in0.getFormat();
                AudioFormat pcm16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
                try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm16, in0)) {
                    long max = (long) (pcm16.getSampleRate() * pcm16.getChannels() * 2 * 30);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    for (int r; bo.size() < max && (r = in.read(b)) > 0; ) bo.write(b, 0, r);
                    data = bo.toByteArray();
                    af = pcm16;
                }
            }
        }
        if (data == null || af == null) return null;
        float[] m = GameRef.mono48k(data, data.length, af.getSampleRate(), af.getChannels(), af.getSampleSizeInBits(), af.isBigEndian());
        if (m == null) return null;
        short[] out = new short[m.length];
        for (int i = 0; i < m.length; i++) out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(m[i])));
        return out;
    }

    private static float rms(short[] d, int off, int len) {
        double s = 0;
        for (int i = off; i < off + len; i++) s += (double) d[i] * d[i];
        return (float) Math.sqrt(s / Math.max(1, len));
    }

    static short[] trim(List<short[]> frames) {
        int n = frames.size();
        float[] rms = new float[n];
        float top = 0;
        for (int i = 0; i < n; i++) { rms[i] = VoiceProc.rms(frames.get(i)); top = Math.max(top, rms[i]); }
        float th = Math.max(VoiceProc.MIN_RMS * 1.5F, top * 0.08F);
        int s0 = -1;
        for (int i = 0; i < n && s0 < 0; i++) if (rms[i] > th) s0 = i;
        if (s0 < 0) { said = "gui.openhouse.hurt.nothing"; return null; }

        int b = s0, quiet = 0;
        boolean cut = false;
        for (int i = s0 + 1; i < n; i++) {
            if (i - s0 >= SOUND_MAX) { cut = true; break; }
            if (rms[i] > th) { b = i; quiet = 0; } else if (++quiet >= GAP) break;
        }
        int a = Math.max(0, s0 - PRE), end, tail_from;
        if (cut) { end = s0 + SOUND_MAX; tail_from = end - a - CUT_FADE; }
        else { end = Math.min(n, b + 1 + TAIL); tail_from = b + 1 - a; }
        int len = end - a;
        tail_from = Math.max(0, Math.min(len, tail_from));
        int fade_n = Math.max(1, len - tail_from);
        double e = 0;
        int cnt = 0, pk = 1;
        for (int i = a; i < end; i++) {
            if (rms[i] > th) { e += (double) rms[i] * rms[i]; cnt++; }
            for (short s : frames.get(i)) pk = Math.max(pk, Math.abs(s));
        }
        float loud = (float) Math.sqrt(e / Math.max(1, cnt));
        float lg = Math.max(0.25F, Math.min(6, LEVEL / Math.max(1, loud)));
        lg = Math.min(lg, PEAK / pk);
        short[] pcm = new short[len * Voice.FRAME];
        for (int i = 0; i < len; i++) {
            short[] f = frames.get(a + i);
            for (int k = 0; k < f.length; k++) {
                int s = i * Voice.FRAME + k;
                float g = lg;
                if (s < 240) g *= s / 240F;
                if (i >= tail_from) {
                    float t = ((i - tail_from) * Voice.FRAME + k) / (float) (fade_n * Voice.FRAME);
                    g *= 0.5F + 0.5F * (float) Math.cos(Math.PI * Math.min(1, t));
                }
                pcm[s] = (short) Math.max(-32768, Math.min(32767, f[k] * g));
            }
        }
        return pcm;
    }

    static void keep(short[] pcm) throws Exception {
        int len = pcm.length / Voice.FRAME;
        if (len <= 0) { said = "gui.openhouse.hurt.nothing"; return; }
        OpusEncoder enc = new OpusEncoder(Voice.RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        enc.setBitrate(48000);
        enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(len);
        byte[] pkt = new byte[1275];
        for (int i = 0; i < len; i++) {
            int m = enc.encode(pcm, i * Voice.FRAME, Voice.FRAME, pkt, 0, pkt.length);
            out.write(m >> 8); out.write(m & 255); out.write(pkt, 0, m);
        }
        byte[] c = out.toByteArray();
        if (c.length > VoiceWire.CLIP_MAX) { said = "gui.openhouse.hurt.toolong"; return; }
        set(c);
        VoiceCfg.hurt_custom = true;
        VoiceCfg.save();
        said = null;
        preview(pcm);
    }

    static void preview(short[] pcm) {
        Thread t = new Thread(() -> {
            try {
                SourceDataLine o = AudioSystem.getSourceDataLine(Voice.FMT);
                o.open(Voice.FMT, 9600);
                o.start();
                byte[] b = new byte[pcm.length * 2];
                float vol = Math.min(1, VoiceCfg.vol);
                for (int i = 0; i < pcm.length; i++) { int v = (int) (pcm[i] * vol); b[i * 2] = (byte) v; b[i * 2 + 1] = (byte) (v >> 8); }
                o.write(b, 0, b.length);
                o.drain();
                o.close();
            } catch (Throwable ignored) { }
        }, "OpenHouse hurt preview");
        t.setDaemon(true);
        t.start();
    }

    private static short[] mine_pcm;
    private static int mine_ver = -1;
    static synchronized short[] mine_pcm() {
        byte[] c = clip;
        if (c == null) return null;
        if (mine_ver != version || mine_pcm == null) { mine_pcm = decode(c, 0); mine_ver = version; }
        return mine_pcm;
    }

    public static void preview_mine() {
        byte[] c = clip;
        short[] p = c == null ? null : decode(c, 0);
        if (p != null) preview(p);
    }

    static final class Theirs { int hash; short[] pcm; }
    private static final Map<Integer, Theirs> theirs = new ConcurrentHashMap<>();
    static final ConcurrentLinkedQueue<float[]> plays = new ConcurrentLinkedQueue<>();

    static short[] decode(byte[] d, int off) {
        List<byte[]> fr = VoiceWire.clip_frames(d, off);
        if (fr == null) return null;
        try {
            OpusDecoder dec = new OpusDecoder(Voice.RATE, 1);
            short[] out = new short[fr.size() * Voice.FRAME];
            short[] f = new short[Voice.FRAME];
            for (int i = 0; i < fr.size(); i++) {
                byte[] p = fr.get(i);
                int n = dec.decode(p, 0, p.length, f, 0, Voice.FRAME, false);
                if (n > 0) System.arraycopy(f, 0, out, i * Voice.FRAME, Math.min(n, Voice.FRAME));
            }
            return out;
        } catch (Throwable e) { return null; }
    }

    static void cap(short[] pcm) {
        int n = pcm.length / Voice.FRAME, pk = 1, cnt = 0;
        double e = 0;
        for (short s : pcm) pk = Math.max(pk, Math.abs(s));
        for (int i = 0; i < n; i++) { float r = rms(pcm, i * Voice.FRAME, Voice.FRAME); if (r > VoiceProc.MIN_RMS * 1.5F) { e += (double) r * r; cnt++; } }
        float loud = (float) Math.sqrt(e / Math.max(1, cnt));
        float g = Math.min(1, Math.min(LEVEL * 1.25F / Math.max(1, loud), PEAK / pk));
        if (g >= 0.999F) return;
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (pcm[i] * g);
    }

    static void got_clip(byte[] d) {
        if (d.length < 11) return;
        int id = i32(d, 1), hash = i32(d, 5);
        short[] pcm = decode(d, 9);
        if (pcm == null) return;
        cap(pcm);
        Theirs t = new Theirs();
        t.hash = hash; t.pcm = pcm;
        theirs.put(id, t);
    }

    static void got_play(byte[] d) {
        if (d.length < 10) return;
        int id = i32(d, 1);
        Theirs t = theirs.get(id);
        if (t == null || t.hash != i32(d, 5)) return;
        plays.add(new float[] { id, (d[9] & 0xFF) / 100F });
    }

    static short[] pcm_of(int id) { Theirs t = theirs.get(id); return t == null ? null : t.pcm; }
    static void forget_all() { theirs.clear(); plays.clear(); }

    private static int i32(byte[] d, int o) { return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16 | (d[o + 2] & 0xFF) << 8 | d[o + 3] & 0xFF; }
}
