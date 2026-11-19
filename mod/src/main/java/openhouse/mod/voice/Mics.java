package openhouse.mod.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

public final class Mics {
    private Mics() { }

    private static List<String> cached = Collections.emptyList();
    private static long cached_at;

    public static synchronized List<String> names() {
        long now = System.nanoTime();
        if (cached_at != 0 && now - cached_at < 2_000_000_000L) return cached;
        List<String> out = new ArrayList<>();
        DataLine.Info want = new DataLine.Info(TargetDataLine.class, Voice.FMT);
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                String n = info.getName();
                if (n == null || n.startsWith("Primary Sound Capture") || out.contains(n)) continue;
                try { if (AudioSystem.getMixer(info).isLineSupported(want)) out.add(n); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        cached = out;
        cached_at = now;
        return out;
    }

    public static String next(String cur) {
        if (cur == null || cur.isEmpty()) return VoiceCfg.DEFAULT;
        List<String> all = names();
        int at = cur.equals(VoiceCfg.DEFAULT) ? -1 : all.indexOf(cur);
        return at + 1 >= all.size() ? "" : all.get(at + 1);
    }

    public static String label(String n, int max) {
        if (n == null || n.isEmpty() || n.equals(VoiceCfg.DEFAULT)) return "Default";
        String s = n.trim();
        int open = s.indexOf('(');
        if (open >= 0 && s.endsWith(")") && s.length() - open > 3) s = s.substring(open + 1, s.length() - 1).trim();
        return s.length() > max ? s.substring(0, max - 2).trim() + ".." : s;
    }

    static TargetDataLine open(String name, int buf) {
        DataLine.Info want = new DataLine.Info(TargetDataLine.class, Voice.FMT);
        TargetDataLine line = null;
        if (name != null && !name.isEmpty()) {
            try {
                for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                    if (!name.equals(info.getName())) continue;
                    Mixer m = AudioSystem.getMixer(info);
                    if (m.isLineSupported(want)) { line = (TargetDataLine) m.getLine(want); break; }
                }
            } catch (Throwable ignored) { line = null; }
        }
        try {
            if (line == null) line = (TargetDataLine) AudioSystem.getLine(want);
            line.open(Voice.FMT, buf);
            return line;
        } catch (Throwable e) {
            if (line != null) try { line.close(); } catch (Throwable ignored) { }
            return null;
        }
    }

    static final String DEAD = "*dead";
    private static volatile String def_name;
    private static volatile long def_at;
    private static volatile boolean def_busy;

    public static String default_name() {
        long now = System.currentTimeMillis();
        if (!def_busy && now - def_at > 20_000) {
            def_busy = true;
            def_at = now;
            Thread t = new Thread(() -> { try { def_name = find_default(); } catch (Throwable ignored) { } finally { def_busy = false; } }, "OpenHouse default mic");
            t.setDaemon(true);
            t.start();
        }
        return def_name;
    }

    private static String find_default() throws InterruptedException {
        List<String> names = new ArrayList<>();
        names.add("");
        for (String n : names()) if (!MicAuto.skip(n)) names.add(n);
        float[][] env = new float[names.size()][];
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            final int k = i;
            Thread t = new Thread(() -> env[k] = envelope(names.get(k)), "OpenHouse default mic " + k);
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) t.join(3000);
        float[] d = env[0];
        if (d == null) return null;
        if (flat(d)) return DEAD;
        String best = null;
        double bc = 0.95;
        for (int i = 1; i < env.length; i++) {
            if (env[i] == null || flat(env[i])) continue;
            double c = corr(d, env[i]);
            if (c > bc) { bc = c; best = names.get(i); }
        }
        return best;
    }

    private static float[] envelope(String name) {
        VoiceMic lm = VoiceMic.live;
        if (lm != null && lm.name.equals(name)) {
            try { Thread.sleep(1250); } catch (InterruptedException e) { return null; }
            return VoiceMic.live == lm ? lm.recent(60) : null;
        }
        TargetDataLine l = open(name, 960 * 2 * 6);
        if (l == null) return null;
        float[] out = new float[60];
        byte[] raw = new byte[960 * 2];
        try {
            l.start();
            for (int k = 0; k < out.length; k++) {
                int got = 0;
                while (got < raw.length) { int n = l.read(raw, got, raw.length - got); if (n <= 0) return null; got += n; }
                double e = 0;
                for (int i = 0; i < raw.length; i += 2) { short v = (short) ((raw[i] & 0xFF) | raw[i + 1] << 8); e += (double) v * v; }
                out[k] = (float) Math.log10(e / 960 + 1);
            }
            return out;
        } catch (Throwable e) {
            return null;
        } finally {
            try { l.stop(); l.close(); } catch (Throwable ignored) { }
        }
    }

    private static boolean flat(float[] a) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : a) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        return hi < 1.2F || hi - lo < 0.05F;
    }

    private static double corr(float[] a, float[] b) {
        double best = -1;
        for (int sh = -3; sh <= 3; sh++) {
            double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
            int n = 0;
            for (int i = 0; i < a.length; i++) {
                int j = i + sh;
                if (j < 0 || j >= b.length) continue;
                sa += a[i]; sb += b[j]; saa += a[i] * a[i]; sbb += b[j] * b[j]; sab += a[i] * b[j]; n++;
            }
            if (n < 20) continue;
            double va = saa - sa * sa / n, vb = sbb - sb * sb / n;
            if (va <= 1e-9 || vb <= 1e-9) continue;
            best = Math.max(best, (sab - sa * sb / n) / Math.sqrt(va * vb));
        }
        return best;
    }

    public static String default_label(int max) {
        String d = default_name();
        if (d == null) return "Default";
        if (d.equals(DEAD)) return "Default (hears nothing)";
        return "Default (" + label(d, max) + ")";
    }
}
