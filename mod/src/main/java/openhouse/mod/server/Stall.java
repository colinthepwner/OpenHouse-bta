package openhouse.mod.server;

public final class Stall {
    private Stall() { }

    static final long MS, EVERY;
    static {
        long ms = 0, ev = 0;
        try {
            String v = System.getenv("OPENHOUSE_TEST_STALL");
            if (v != null) { String[] p = v.split(","); ms = Long.parseLong(p[0].trim()); ev = Long.parseLong(p[1].trim()); }
        } catch (RuntimeException ignored) { }
        MS = ms; EVERY = ev;
    }
    static long last = System.currentTimeMillis();

    public static void tick() {
        if (MS <= 0 || EVERY <= 0) return;
        long now = System.currentTimeMillis();
        if (now - last < EVERY) return;
        last = now + MS;
        try { Thread.sleep(MS); } catch (InterruptedException ignored) { }
    }
}
