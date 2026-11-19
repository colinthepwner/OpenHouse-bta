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
}
