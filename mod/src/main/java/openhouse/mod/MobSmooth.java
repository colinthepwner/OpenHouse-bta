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

    static boolean pos = true;

    public static void stamp(long ms, boolean has_rot, boolean has_pos) { stamp = ms; rot = has_rot; pos = has_pos; }
    public static void stamp(long ms, boolean has_rot) { stamp(ms, has_rot, true); }
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
    public double lx, ly, lz;
    double conf;
    double clock = Double.NaN;
    int per = 3;
    double g3 = 1, goff;
    boolean still;
    boolean wet;
    int calm, calm_t;
    static final double LAND_VY = -1.0 / 32 / TICK;
    static final double CALM_SPAN = num("ms.calm", 0), CALM_BLEND = num("ms.calmblend", 150), CALM_CONF = num("ms.calmconf", 0.9);
    double lt;

    double yw, yv, yt = Double.NaN, yconf, ydisp;
    boolean ystill;
    double ystep = YSTEP, ylast = Double.NaN;
    static final double YSTEP = 8 * 360 / 256.0;
    static final double YEASE = num("ms.yease", 0.5), YLEAD = num("ms.ylead", 1), YB = num("ms.yb", 0.6), YJIT = num("ms.yjit", 12);

    public boolean ready() { return !Double.isNaN(st); }

    public void wet(boolean w) {
        if (w == wet) return;
        if (!ready()) { wet = w; return; }
        double now = now_ms(), cx = dx(now), cy = dy(now), cz = dz(now);
        wet = w;
        blend(now, cx, cy, cz);
    }

    public void hurt(boolean h) {
        if (h && calm == 0 && ready()) { double now = now_ms(), cx = dx(now), cy = dy(now), cz = dz(now); calm = 4; blend(now, cx, cy, cz); }
        if (h) { calm = 4; calm_t = 0; }
    }
    public void reset() { st = Double.NaN; clock = Double.NaN; bt = Double.NaN; shown = false; yt = Double.NaN; ylast = Double.NaN; ystep = YSTEP; calm = 0; }

    public void sample(double x, double y, double z) {
        long arr = stamp != 0 ? stamp : wall.getAsLong();
        stamp = 0;
        boolean moved = pos; pos = true;

        if (!moved && ready()) { lt = tick_lock(arr); if (per > 1) lt = st + Math.max(1, Math.round((lt - st) / (per * TICK))) * per * TICK; return; }
        double was_y = ly;
        lx = x; ly = y; lz = z;
        double t = tick_lock(arr);
        if (!ready()) { put(x, y, z, t); return; }

        int ticks = (int) Math.round((t - st) / TICK);
        g3 *= 0.97; goff *= 0.97;
        if (ticks >= 1 && ticks <= 12) { if (ticks % 3 == 0) g3 += 1; else goff += 1; }
        per = goff > 0.2 * (g3 + goff) ? 1 : 3;

        if (t - st < TICK * 0.5) st = t - per * TICK;

        if (per > 1) t = st + Math.max(1, Math.round((t - st) / (per * TICK))) * per * TICK;
        double now = now_ms();
        double cx = dx(now), cy = dy(now), cz = dz(now);
        la = now;

        while (FIT && t - st >= 2 * per * TICK && conf > 0.3) {
            double d1 = t - st, d0 = d1 - per * TICK;
            if (miss(x, y, z, d0) < 0.6 * miss(x, y, z, d1)) t -= per * TICK; else break;
        }
        double dt = t - st;

        double fx0 = fx, fy0 = fy, fz0 = fz;
        double ox = fx + vx * dt, oy = fy + vy * dt, oz = fz + vz * dt;
        if (still) {

            ox = fx + clamp(vx * dt); oy = fy + clamp(vy * dt); oz = fz + clamp(vz * dt);
            if (dt > 4 * TICK) vx = vy = vz = 0;
        }
        double rx = x - ox, ry = y - oy, rz = z - oz;
        double rh = Math.sqrt(rx * rx + rz * rz), r = Math.sqrt(rh * rh + ry * ry);
        if (r > SNAP) { put(x, y, z, t); return; }

        double a = A, b = B, ay = A, by = B;
        if (rh > TURN) { double k = Math.min(1, (rh - TURN) / TURN); a += (1 - a) * k; b += (1 - b) * k; }
        if (Math.abs(ry) > TURN) { double k = Math.min(1, (Math.abs(ry) - TURN) / TURN); ay += (1 - ay) * k; by += (1 - by) * k; }
        fx = ox + a * rx; fy = oy + ay * ry; fz = oz + a * rz;

        double sx = still ? x - fx0 - vx * dt : rx, sy = still ? y - fy0 - vy * dt : ry, sz = still ? z - fz0 - vz * dt : rz;
        vx += b * sx / dt; vy += by * sy / dt; vz += b * sz / dt;

        double sp = Math.sqrt(vx * vx + vz * vz), cap = 1.0 / TICK;
        if (sp > cap) { vx *= cap / sp; vz *= cap / sp; }
        if (Math.abs(vy) > cap * 2) vy = Math.signum(vy) * cap * 2;
        conf = conf * 0.75 + 0.25 * (rh < 0.04 ? 1 : rh < 0.15 ? 0.5 : 0);

        boolean land = vy < LAND_VY && y >= was_y - 1e-6;
        if (land) { fy = y; vy = 0; }
        st = t;
        lt = t;
        still = false;
        blend(now, cx, land ? Math.max(cy, y) : cy, cz);
    }

    void blend(double now, double cx, double cy, double cz) {
        double gx = at_x(now) - cx, gy = at_y(now) - cy, gz = at_z(now) - cz;
        if (gx * gx + gy * gy + gz * gz > 9) { bt = Double.NaN; return; }
        bx = cx; by = cy; bz = cz; bvx = dvx; bvy = dvy; bvz = dvz; bt = now;

        double qvy = (at_y(now) - at_y(now - 10)) / 10;
        if (gy > 0 ? dvy < qvy : dvy > qvy) bvy = qvy;

        double qvx = (at_x(now) - at_x(now - 10)) / 10, qvz = (at_z(now) - at_z(now - 10)) / 10;
        double rx = dvx - qvx, rz = dvz - qvz, el = Math.hypot(gx, gz), len = blen();
        if (el < 1e-6) return;
        double ux = -gx / el, uz = -gz / el, rp = rx * ux + rz * uz;
        double slack = PVBE / (0.148 * len);
        double keep = rp > 0 ? Math.min(rp, slack) : Math.max(rp, -4 * el / len - slack);
        bvx = qvx + rx - (rp - keep) * ux; bvz = qvz + rz - (rp - keep) * uz;
    }
    static final double PVBE = 0.02;
    double blen() { return calm > 0 ? CALM_BLEND : BLEND * Math.max(1, Math.min(3, jit / JIT_REF)); }

    double bl(double now, double q, double qv, double b, double bv) {

        double len = blen();
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
        double a = x - fx - vx * dt, c = z - fz - vz * dt;
        return Math.sqrt(a * a + c * c);
    }

    void put(double x, double y, double z, double t) {
        fx = x; fy = y; fz = z; vx = vy = vz = 0; st = t; lt = t; conf = 0; still = false;
        bt = Double.NaN; dvx = dvy = dvz = 0; shown = false; calm = 0; la = now_ms();
    }

    public void tick() {

        double w = wall.getAsLong();
        if (Double.isNaN(clock) || Math.abs(w - clock) > 500) clock = w;
        else { clock += TICK; clock += (w - clock) * 0.1; }
        double now = clock;

        if (calm > 0) calm_t++;
        if (calm > 0 && (still || conf > CALM_CONF || calm_t > 40) && --calm == 0) { double cx = dx(now), cy = dy(now), cz = dz(now); blend(now, cx, cy, cz); }

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
        if (calm > 0) return Math.max(0, Math.min(age, CALM_SPAN));
        if (age > full) age = full + (age - full) * 0.25;
        age = Math.min(age, full + TICK * 3);
        return Math.max(0, age + (lead ? LEAD * conf : 0));
    }

    double dsp(double v, double now, boolean lead) {
        double d = v * span(now, lead);
        return still ? clamp(d) : d;
    }

    static double clamp(double d) { return Math.max(-STEP, Math.min(STEP, d)); }

    static final double CAP = num("ms.cap", STEP), CAPJ = num("ms.capj", 30), JK = num("ms.jk", 4);
    double la = Double.NaN;
    double hx, hz;
    void at_xz(double now) {
        double x = fx + dsp(vx, now, true), z = fz + dsp(vz, now, true), l = Math.hypot(vx, vz);
        if (l > 1e-12) {

            double age = now - st, run = still ? 0 : (per > 1 ? Math.min(Math.max(0, now - la), per * TICK) : 0) + Math.max(0, Math.min(Math.max(CAPJ, jit * JK), age - per * TICK));
            double e = (x - lx) * vx / l + (z - lz) * vz / l - CAP - l * run;
            if (e > 0) { x -= e * vx / l; z -= e * vz / l; }
        }
        hx = x; hz = z;
    }
    double at_x(double now) { at_xz(now); return hx; }
    double at_z(double now) { at_xz(now); return hz; }

    double at_y(double now) { if (wet) return fy; double d = dsp(vy, now, true) * 0.5; return fy + Math.max(-0.5, Math.min(0.5, d)); }
}
