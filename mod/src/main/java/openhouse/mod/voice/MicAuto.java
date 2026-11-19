package openhouse.mod.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.sound.sampled.TargetDataLine;

final class MicAuto {
    private MicAuto() { }

    static final long SCOUT_MS = 2500;
    static final long QUIET_MS = 20_000;
    static final long DEAD_MS = 2_000;
    static final long TALK_MS = 90_000;
    static final Pattern SKIP = Pattern.compile("(?i)stereo mix|what u hear|wave out|loopback|hands[- ]free|ag audio");

    static volatile String pick = "";
    static volatile String said;
    private static volatile boolean scouting;
    private static long last_scout;

    static volatile MicStats window;
    private static String pending;
    private static int pending_n;
    private static long switched_at;

    static boolean skip(String name) { return SKIP.matcher(name).find(); }

    static void check(String cur_name, long quiet_ms, long dead_ms, boolean talking) {
        long now = System.currentTimeMillis(), since = now - last_scout;
        boolean dead = dead_ms >= DEAD_MS;
        if (scouting) return;
        boolean go = dead ? since >= 8_000 : quiet_ms >= QUIET_MS ? since >= 30_000 : talking && since >= TALK_MS;
        if (!go) return;
        last_scout = now;
        scouting = true;
        MicStats cur = window = new MicStats();
        Thread t = new Thread(() -> { try { scout(cur_name, cur, dead); } finally { scouting = false; window = null; } }, "OpenHouse mic scout");
        t.setDaemon(true);
        t.start();
    }

    private static void scout(String cur_name, MicStats cur, boolean cur_dead) {
        List<String> names = new ArrayList<>();
        for (String n : Mics.names()) if (!n.equals(cur_name) && !skip(n)) names.add(n);
        if (!cur_name.isEmpty()) names.add("");
        if (names.isEmpty()) return;

        List<Thread> ts = new ArrayList<>();
        MicStats[] st = new MicStats[names.size()];
        for (int i = 0; i < names.size(); i++) {
            final int k = i;
            st[k] = new MicStats();
            Thread t = new Thread(() -> listen(names.get(k), st[k]), "OpenHouse mic scout " + k);
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) try { t.join(SCOUT_MS + 1500); } catch (InterruptedException e) { return; }
        int best = -1;
        float bs = 0;
        for (int i = 0; i < st.length; i++) {
            if (LOG) openhouse.mod.OpenHouseMod.LOG.info("[voice] scout {}: {}", Mics.label(names.get(i), 40), st[i]);
            if (st[i].frames < 20 || st[i].dead()) continue;
            float s = st[i].score();
            if (s > bs) { bs = s; best = i; }
        }
        float cs = cur.score();
        if (LOG) openhouse.mod.OpenHouseMod.LOG.info("[voice] scout current {}: {} (dead {})", Mics.label(cur_name, 40), cur, cur_dead);
        String to = null;
        if (best >= 0 && bs >= 0.08F && bs > cs * 1.4F) {

            String b = names.get(best);
            if (cur_dead || cs < 0.02F) to = b;
            else if (b.equals(pending) && ++pending_n >= 2) to = b;
            else { pending = b; pending_n = 1; }
        } else { pending = null; pending_n = 0; }
        if (to == null && cur_dead && !cur_name.isEmpty()) to = "";
        if (to == null || to.equals(pick)) return;
        if (!cur_dead && System.currentTimeMillis() - switched_at < 30_000) return;
        switched_at = System.currentTimeMillis();
        pending = null; pending_n = 0;
        pick = to;
        said = Mics.label(to, 24);
        openhouse.mod.OpenHouseMod.LOG.info("[voice] Auto mic: now {}", to.isEmpty() ? "the system default" : to);
    }

    private static void listen(String name, MicStats st) {
        TargetDataLine l = Mics.open(name, 960 * 2 * 6);
        if (l == null) return;
        byte[] raw = new byte[960 * 2];
        short[] f = new short[960];
        long end = System.currentTimeMillis() + SCOUT_MS;
        try {
            l.start();
            while (System.currentTimeMillis() < end) {
                int got = 0;
                while (got < raw.length) { int n = l.read(raw, got, raw.length - got); if (n <= 0) break; got += n; }
                if (got < raw.length) break;
                for (int i = 0; i < f.length; i++) f[i] = (short) ((raw[i * 2] & 0xFF) | raw[i * 2 + 1] << 8);
                st.add(f);
            }
        } catch (Throwable ignored) {
        } finally {
            try { l.stop(); l.close(); } catch (Throwable ignored) { }
        }
    }

    static final boolean LOG = Boolean.getBoolean("openhouse.voicelog");
}
