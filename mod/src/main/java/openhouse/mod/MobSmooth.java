package openhouse.mod;

public final class MobSmooth {
    public static final boolean ON = !"0".equals(System.getProperty("openhouse.mobsmooth", "1"));
    static final double TICK = 50.0;
    static final double LEAD = num("openhouse.mobsmooth.lead", 50);

    static final double A = num("ms.a", 0.9), B = num("ms.b", 0.6), BLEND = num("ms.blend", 300), JIT_REF = num("ms.jitref", 6);
    static final double SNAP = 8.0;
    static final double TURN = num("ms.turn", 0.08);
    static final boolean FIT = num("ms.fit", 1) > 0;
    static final double STEP = 2.0 / 32.0;
    static double num(String k, double d) { try { return Double.parseDouble(System.getProperty(k, "" + d)); } catch (RuntimeException e) { return d; } }
    public static java.util.function.LongSupplier wall = System::currentTimeMillis;

    static double grid = Double.NaN;
    static long stamp;
    static double jit = 10;

    static boolean rot;

    public static void stamp(long ms, boolean has_rot) { stamp = ms; rot = has_rot; }
    public static void stamp(long ms) { stamp(ms, true); }
    public static boolean take_rot() { boolean r = rot; rot = false; return r; }
    public static long spawn;

    static double tick_lock(long arr) {
        if (Double.isNaN(grid) || Math.abs(arr - grid) > 60000) grid = arr;

        double k = Math.floor((arr - grid) / TICK + 0.2);
        double pt = grid + k * TICK;
        double e = arr - pt;
        grid += e < 0 ? e * 0.1 : e * 0.005;
        jit += (Math.min(Math.abs(e), 50) - jit) * 0.02;
        return pt;
    }

    double fx, fy, fz;
    double vx, vy, vz;
    double st = Double.NaN;
    double bx, by, bz, bvx, bvy, bvz, bt = Double.NaN;
    double dvx, dvy, dvz;
    boolean shown;
    public double px, py, pz;
    double conf;
    double clock = Double.NaN;
    int per = 3;
    final double[] gaps = {0, 0, 0, 1};
    boolean still;
    double lt;

    double yw, yv, yt = Double.NaN, yconf, ydisp;
    boolean ystill;
    double ystep = YSTEP, ylast = Double.NaN;
    static final double YSTEP = 8 * 360 / 256.0;
    static final double YEASE = num("ms.yease", 0.5), YLEAD = num("ms.ylead", 1), YB = num("ms.yb", 0.6), YJIT = num("ms.yjit", 12);

    public boolean ready() { return !Double.isNaN(st); }
    public void reset() { st = Double.NaN; clock = Double.NaN; bt = Double.NaN; shown = false; yt = Double.NaN; ylast = Double.NaN; ystep = YSTEP; }

    public void sample(double x, double y, double z) {
        long arr = stamp != 0 ? stamp : wall.getAsLong();
        stamp = 0;
        double t = tick_lock(arr);
        if (!ready()) { put(x, y, z, t); return; }

        int ticks = (int) Math.round((t - st) / TICK);
        for (int i = 1; i <= 3; i++) gaps[i] *= 0.97;
        if (ticks >= 1 && ticks <= 3) gaps[ticks] += 1;
        per = gaps[1] > 0.25 * (gaps[1] + gaps[2] + gaps[3]) ? 1 : gaps[3] >= gaps[2] ? 3 : 2;

        if (t <= st) st = t - per * TICK;

        if (per > 1) t = st + Math.max(1, Math.round((t - st) / (per * TICK))) * per * TICK;
        double now = now_ms();
        double cx = dx(now), cy = dy(now), cz = dz(now);

        while (FIT && t - st >= 2 * per * TICK && conf > 0.3) {
            double d1 = t - st, d0 = d1 - per * TICK;
            if (miss(x, y, z, d0) < 0.6 * miss(x, y, z, d1)) t -= per * TICK; else break;
        }
        double dt = t - st;

        double ox = fx + vx * dt, oy = fy + vy * dt, oz = fz + vz * dt;
        if (still) {

            ox = fx + clamp(vx * dt); oy = fy + clamp(vy * dt); oz = fz + clamp(vz * dt);
            if (dt > 4 * TICK) vx = vy = vz = 0;
        }
        double rx = x - ox, ry = y - oy, rz = z - oz;
        double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (r > SNAP) { put(x, y, z, t); return; }

        double a = A, b = B;
        if (r > TURN) { double k = Math.min(1, (r - TURN) / TURN); a += (1 - a) * k; b += (1 - b) * k; }
        fx = ox + a * rx; fy = oy + a * ry; fz = oz + a * rz;
        vx += b * rx / dt; vy += b * ry / dt; vz += b * rz / dt;

        double sp = Math.sqrt(vx * vx + vz * vz), cap = 1.0 / TICK;
        if (sp > cap) { vx *= cap / sp; vz *= cap / sp; }
        if (Math.abs(vy) > cap * 2) vy = Math.signum(vy) * cap * 2;
        conf = conf * 0.75 + 0.25 * (r < 0.04 ? 1 : r < 0.15 ? 0.5 : 0);
        st = t;
        lt = t;
        still = false;
        blend(now, cx, cy, cz);
    }

    void blend(double now, double cx, double cy, double cz) {
        double gx = at_x(now) - cx, gy = at_y(now) - cy, gz = at_z(now) - cz;
        if (gx * gx + gy * gy + gz * gz > 9) { bt = Double.NaN; return; }
        bx = cx; by = cy; bz = cz; bvx = dvx; bvy = dvy; bvz = dvz; bt = now;
    }

    double bl(double now, double q, double qv, double b, double bv) {

        double len = BLEND * Math.max(1, Math.min(3, jit / JIT_REF));
        if (Double.isNaN(bt) || now - bt >= len) return q;
        double u = (now - bt) / len, vb = bv + (qv - bv) * u, pa = b + vb * (now - bt);
        return pa + (q - pa) * u;
    }

    double dx(double now) { return bl(now, at_x(now), (at_x(now) - at_x(now - 10)) / 10, bx, bvx); }
    double dy(double now) { return bl(now, at_y(now), (at_y(now) - at_y(now - 10)) / 10, by, bvy); }
    double dz(double now) { return bl(now, at_z(now), (at_z(now) - at_z(now - 10)) / 10, bz, bvz); }

    public void yaw(double meas) {
        double t = lt;
        if (!Double.isNaN(ylast)) {
            double jump = Math.abs(wrap(meas - ylast));
            if (jump >= 360 / 256.0 * 0.9) ystep = Math.max(360 / 256.0, Math.min(ystep * 1.01, jump));
        }
        ylast = meas;
        if (Double.isNaN(yt)) { yw = ydisp = meas; yv = 0; yt = t; yconf = 0; ystill = false; return; }
        double dt = t - yt;
        if (dt <= 0) dt = TICK;
        double pred = yw + (ystill ? yclamp(yv * dt) : yv * dt);
        double r = wrap(meas - pred);
        double a = A, b = YB;
        double turn = Math.max(ystep, 4);
        if (Math.abs(r) > turn) { double k = Math.min(1, (Math.abs(r) - turn) / turn); a += (1 - a) * k; b += (1 - b) * k; }
        if (ystill && dt > 4 * TICK) yv = 0;
        yw = pred + a * r;
        yv += b * r / dt;
        double cap = 45 / TICK;
        if (Math.abs(yv) > cap) yv = Math.signum(yv) * cap;
        yconf = yconf * 0.75 + 0.25 * (Math.abs(r) < 3 ? 1 : Math.abs(r) < 10 ? 0.5 : 0);
        yt = t;
        ystill = false;
    }

    double yaw_at(double now) {
        double age = now - yt, full = (per + 1) * TICK;
        if (age > full) age = full + (age - full) * 0.25;
        age = Math.min(age, full + TICK * 3);
        double d = yv * Math.max(0, age + LEAD * YLEAD * yconf);
        return yw + (ystill ? yclamp(d) : d);
    }

    double yclamp(double d) { return Math.max(-ystep, Math.min(ystep, d)); }
    static double wrap(double d) { d %= 360; if (d >= 180) d -= 360; if (d < -180) d += 360; return d; }

    public boolean has_yaw() { return !Double.isNaN(yt); }

    public double head() { return ydisp; }

    double miss(double x, double y, double z, double dt) {
        double a = x - fx - vx * dt, b = y - fy - vy * dt, c = z - fz - vz * dt;
        return Math.sqrt(a * a + b * b + c * c);
    }

    void put(double x, double y, double z, double t) {
        fx = x; fy = y; fz = z; vx = vy = vz = 0; st = t; lt = t; conf = 0; still = false;
        bt = Double.NaN; dvx = dvy = dvz = 0; shown = false;
    }

    public void tick() {

        double w = wall.getAsLong();
        if (Double.isNaN(clock) || Math.abs(w - clock) > 500) clock = w;
        else { clock += TICK; clock += (w - clock) * 0.1; }
        double now = clock;

        if (!still && now - st > (per + 1) * TICK + Math.max(40, jit * 4)) {

            double cx = dx(now), cy = dy(now), cz = dz(now);
            still = true;
            blend(now, cx, cy, cz);
        }
        if (has_yaw()) {
            if (!ystill && now - yt > (per + 1) * TICK + Math.max(40, jit * 4)) ystill = true;
            ydisp += wrap(yaw_at(now) - ydisp) * YEASE / Math.max(1, Math.min(3, jit / YJIT));
        }
        double nx = dx(now), ny = dy(now), nz = dz(now);
        if (shown) { dvx = (nx - px) / TICK; dvy = (ny - py) / TICK; dvz = (nz - pz) / TICK; }
        px = nx; py = ny; pz = nz; shown = true;
    }

    double now_ms() { return Double.isNaN(clock) ? wall.getAsLong() : clock; }

    double span(double now, boolean lead) {
        double age = now - st, full = (per + 1) * TICK;
        if (age > full) age = full + (age - full) * 0.25;
        age = Math.min(age, full + TICK * 3);
        return Math.max(0, age + (lead ? LEAD * conf : 0));
    }

    double dsp(double v, double now, boolean lead) {
        double d = v * span(now, lead);
        return still ? clamp(d) : d;
    }

    static double clamp(double d) { return Math.max(-STEP, Math.min(STEP, d)); }

    double at_x(double now) { return fx + dsp(vx, now, true); }
    double at_z(double now) { return fz + dsp(vz, now, true); }

    double at_y(double now) { double d = dsp(vy, now, true) * 0.5; return fy + Math.max(-0.5, Math.min(0.5, d)); }
}
