package openhouse.mod.voice;

import javax.sound.sampled.TargetDataLine;

final class VoiceMic implements Runnable {
    static final int RETRY_MS = 3000, LINE_FRAMES = 6;

    final String name;
    final boolean test;
    private final Thread t;
    private volatile boolean run = true;
    private volatile TargetDataLine line;
    volatile boolean clean;
    volatile String problem;
    volatile VoiceProc proc;
    volatile boolean aec = true;
    volatile Boolean ptt;
    private VoiceEcho echo;

    private float hx1, hx2, hy1, hy2;
    private static final float HB0, HB1, HB2, HA1, HA2;
    static {
        double w = 2 * Math.PI * 100 / 48000, al = Math.sin(w) / (2 * 0.707), c = Math.cos(w), a0 = 1 + al;
        HB0 = (float) ((1 + c) / 2 / a0); HB1 = (float) (-(1 + c) / a0); HB2 = HB0; HA1 = (float) (-2 * c / a0); HA2 = (float) ((1 - al) / a0);
    }
    private void hipass(short[] f) {
        for (int i = 0; i < f.length; i++) {
            float x = f[i], yv = HB0 * x + HB1 * hx1 + HB2 * hx2 - HA1 * hy1 - HA2 * hy2;
            hx2 = hx1; hx1 = x; hy2 = hy1; hy1 = yv;
            f[i] = (short) Math.max(-32768, Math.min(32767, yv));
        }
    }

    private static final String FAKE_ECHO = System.getProperty("openhouse.voice.fakeecho");
    private final float[] fe = new float[960];

    private static final boolean NO_FILTER = Boolean.getBoolean("openhouse.voice.nofilter");

    static volatile VoiceMic live;
    private final float[] env_ring = new float[64];
    private int env_at;
    float[] recent(int n) {
        synchronized (env_ring) {
            float[] r = new float[n];
            for (int i = 0; i < n; i++) r[i] = env_ring[Math.floorMod(env_at - n + i, env_ring.length)];
            return r;
        }
    }

    private void run_frame(VoiceProc p, short[] f, VoiceProc.Out out) throws Exception {
        long at = VoiceEcho.clock();
        double en = 0;
        for (short v : f) en += (double) v * v;
        synchronized (env_ring) { env_ring[env_at] = (float) Math.log10(en / f.length + 1); env_at = (env_at + 1) % env_ring.length; }
        auto_feed(f, p.talking());
        if (FAKE_ECHO != null) {
            String[] a = FAKE_ECHO.split(",");
            int lag = Integer.parseInt(a[0].trim()) * 48;
            float g = Float.parseFloat(a[1].trim());

            VoiceEcho.read(echo.frame_start(at, f.length) - lag, fe, 0, f.length);
            for (int i = 0; i < f.length; i++) f[i] = (short) Math.max(-32768, Math.min(32767, f[i] + fe[i] * g));
        }
        hipass(f);
        boolean far = aec && VoiceEcho.far_recent();
        if (far && !NO_FILTER) echo.process(f, at);

        p.far_lvl = far ? VoiceEcho.far_level(at - f.length) : 0;
        p.clean = clean;
        p.process(f, out, far && echo.echo_now, ptt);
    }

    final boolean auto;
    private long last_voice = System.currentTimeMillis(), dead_since;
    private MicStats sec = new MicStats();
    private void auto_feed(short[] f, boolean talking) {
        MicStats w = MicAuto.window;
        if (w != null) w.add(f);
        sec.add(f);
        long now = System.currentTimeMillis();
        if (talking) last_voice = now;
        if (sec.frames >= 50) {
            if (sec.dead()) { if (dead_since == 0) dead_since = now; } else dead_since = 0;
            sec = new MicStats();
            if (auto) MicAuto.check(name, now - last_voice, dead_since == 0 ? 0 : now - dead_since, talking);
        }
    }

    boolean deaf() { long d = dead_since; return d != 0 && System.currentTimeMillis() - d >= MicAuto.DEAD_MS; }
    int echo_delay() { VoiceEcho e = echo; return e == null ? -1 : e.delay_ms(); }
    float echo_erle() { VoiceEcho e = echo; return e == null ? 0 : e.erle; }
    String gate() { VoiceProc p = proc; return p == null ? "-" : p.gate(); }

    VoiceMic(String name, boolean clean, boolean test, boolean auto) {
        this.auto = auto;
        this.name = name == null ? "" : name;
        this.clean = clean;
        this.test = test;
        t = new Thread(this, "OpenHouse voice in");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
    }

    void start() { t.start(); }

    boolean talking() { VoiceProc p = proc; return p != null && p.talking(); }
    float level() { VoiceProc p = proc; return p == null ? 0 : p.level; }

    void stop() {
        run = false;
        TargetDataLine l = line;
        if (l != null) try { l.stop(); l.close(); } catch (Throwable ignored) { }
    }

    public void run() {
        VoiceProc p;
        try { p = new VoiceProc(); } catch (Throwable e) {
            problem = "Voice encoder failed";
            openhouse.mod.OpenHouseMod.LOG.warn("[voice] {}: {}", problem, e.toString());
            return;
        }
        proc = p;
        VoiceProc.Out out = (b, l) -> { if (!test) Voice.send(b, l); Voice.self(b, l); };
        echo = new VoiceEcho();
        byte[] raw = new byte[Voice.FRAME * 2];
        short[] f = new short[Voice.FRAME];
        try {
            String file = System.getProperty("openhouse.voice.file");
            if (file != null) { from_file(new java.io.File(file), p, out, raw, f); return; }
            while (run) {
                TargetDataLine l = Mics.open(name, raw.length * LINE_FRAMES);
                if (l == null) { problem = "No microphone"; nap(RETRY_MS); continue; }
                line = l;
                live = this;
                if (!run) { l.close(); break; }
                problem = null;
                l.start();
                while (run) {
                    int got = 0;
                    while (got < raw.length && run) {
                        int n = l.read(raw, got, raw.length - got);
                        if (n <= 0) { if (!l.isOpen() || !run) break; nap(2); continue; }
                        got += n;
                    }
                    if (got < raw.length) break;
                    for (int i = 0; i < f.length; i++) f[i] = (short) ((raw[i * 2] & 0xFF) | raw[i * 2 + 1] << 8);
                    run_frame(p, f, out);
                }
                p.end(out);
                try { l.close(); } catch (Throwable ignored) { }
                line = null;
                if (run) { problem = "Microphone lost"; nap(RETRY_MS); }
            }
        } catch (Throwable e) {
            problem = "Microphone failed";
            openhouse.mod.OpenHouseMod.LOG.warn("[voice] mic thread stopped: {}", e.toString());
        } finally {
            if (live == this) live = null;
            p.end(out);
            TargetDataLine l = line;
            if (l != null) try { l.close(); } catch (Throwable ignored) { }
            p.close();
        }
    }

    private void from_file(java.io.File file, VoiceProc p, VoiceProc.Out out, byte[] raw, short[] f) throws Exception {
        openhouse.mod.OpenHouseMod.LOG.info("[voice] mic is the file {}", file);
        long next = System.nanoTime();
        javax.sound.sampled.AudioInputStream in = null;
        try {
            while (run) {
                if (in == null) in = javax.sound.sampled.AudioSystem.getAudioInputStream(file);
                int got = 0;
                while (got < raw.length) { int n = in.read(raw, got, raw.length - got); if (n < 0) break; got += n; }
                if (got < raw.length) { in.close(); in = null; continue; }
                for (int i = 0; i < f.length; i++) f[i] = (short) ((raw[i * 2] & 0xFF) | raw[i * 2 + 1] << 8);
                run_frame(p, f, out);
                next += 20_000_000L;
                long w = (next - System.nanoTime()) / 1_000_000L;
                if (w > 0) nap((int) w);
            }
        } finally { if (in != null) in.close(); }
    }

    private void nap(int ms) {
        long until = System.currentTimeMillis() + ms;
        while (run && System.currentTimeMillis() < until) {
            try { Thread.sleep(Math.max(1, Math.min(50, until - System.currentTimeMillis()))); } catch (InterruptedException e) { return; }
        }
    }
}
