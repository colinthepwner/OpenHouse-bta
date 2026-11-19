package openhouse.mod.voice;

import io.github.jaredmdobson.concentus.OpusDecoder;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.block.Block;
import net.minecraft.core.block.Blocks;
import net.minecraft.core.block.material.Material;
import net.minecraft.core.block.material.Materials;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

final class VoiceOut implements Runnable {
    static final int PREBUFFER = 3, QUEUED = 4, MAX_PENDING = 14, TRIM_TO = 5;
    static final long FORGET_NS = 20_000_000_000L, SPEAKING_NS = 300_000_000L;
    static final float FULL = 5, REACH = 48, EDGE_FROM = 0.7F, ROLLOFF = 0.09F;
    static final float WALL_GAIN_FLOOR = 0.25F, WALL_HF_FLOOR = 0.05F;
    static final float HF_PER_BLOCK = 0.9F, GAIN_PER_BLOCK = 0.35F;
    static final float WATER_HF = 0.15F, WATER_GAIN = 0.6F;

    static final class Spk {
        final int id;
        final OpusDecoder dec;
        final ConcurrentLinkedQueue<short[]> pending = new ConcurrentLinkedQueue<>();
        volatile long last;
        volatile boolean ended = true, known;
        volatile int loud;
        final VoiceLevel.State lv = new VoiceLevel.State();
        volatile float x, y, z, gain, hf = 1, dist, walls;

        int src, filter;
        long ctx;
        long ref_next;
        final ArrayDeque<Integer> free = new ArrayDeque<>();
        Spk(int id) throws Exception { this.id = id; dec = new OpusDecoder(Voice.RATE, 1); }
    }

    private final Map<Integer, Spk> spk = new ConcurrentHashMap<>();
    private final ShortBuffer up = BufferUtils.createShortBuffer(Voice.FRAME);
    private Thread t;
    private volatile boolean run;
    private boolean efx_tried, efx;
    private long efx_ctx;
    volatile float vol = 1;
    volatile String problem;

    synchronized void start() {
        if (run) return;
        run = true;
        t = new Thread(this, "OpenHouse voice out");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        t.start();
    }

    synchronized void stop() {
        if (!run) return;
        run = false;
        Thread th = t;
        t = null;
        if (th != null) try { th.join(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        spk.clear();
    }

    void receive(int id, byte[] d, int off, int len, int loud) {
        if (!run) return;
        Spk s = spk.get(id);
        if (s == null) {
            if (len == 0) return;
            try { s = new Spk(id); } catch (Exception e) { return; }
            Spk had = ((ConcurrentHashMap<Integer, Spk>) spk).putIfAbsent(id, s);
            if (had != null) s = had;
        }
        synchronized (s) {
            if (len == 0) { s.ended = true; return; }
            short[] pcm = new short[Voice.FRAME];
            int n;
            try { n = s.dec.decode(d, off, len, pcm, 0, Voice.FRAME, false); } catch (Exception e) { return; }
            if (n <= 0) return;
            VoiceLevel.apply(s.lv, pcm);
            s.ended = false;
            s.loud = loud;
            s.last = System.nanoTime();
            s.pending.add(pcm);
            if (s.pending.size() > MAX_PENDING) while (s.pending.size() > TRIM_TO) s.pending.poll();
        }
    }

    private volatile int played, starts;
    private volatile String me_at = "";

    String describe() {
        StringBuilder b = new StringBuilder("played=" + played + " starts=" + starts + " hurt_al=" + hurt_played);
        played = 0; starts = 0;
        for (Spk s : spk.values()) b.append(String.format(" me=%s [%d %s at %.1f %.1f %.1f d=%.1f walls=%.2f gain=%.2f hf=%.2f pending=%d src=%d]", me_at, s.id, s.known ? "placed" : "unplaced", s.x, s.y, s.z, s.dist, s.walls, s.gain, s.hf, s.pending.size(), s.src));
        return b.toString();
    }

    float loudness(int id) {
        Spk s = spk.get(id);
        return s == null ? 0 : Math.max(0, (s.loud - 110) / 145F);
    }

    float loudest_talker() {
        float m = 0;
        long now = System.nanoTime();
        for (Spk s : spk.values()) if (s.id != Voice.SELF && s.known && !s.ended && now - s.last < SPEAKING_NS) m = Math.max(m, s.gain * vol);
        return Math.min(1, m);
    }

    boolean speaking(int id) {
        Spk s = spk.get(id);
        return s != null && !s.ended && System.nanoTime() - s.last < SPEAKING_NS;
    }

    void place(Minecraft mc) {
        Spk me_spk = spk.get(Voice.SELF);
        if (me_spk != null) { me_spk.known = true; me_spk.gain = 1; me_spk.hf = 1; }
        World w = mc.currentWorld;
        Player me = mc.thePlayer;
        if (w == null || me == null) return;
        double lx = me.x, ly = me.bb.minY + me.getHeadHeight(), lz = me.z;
        boolean wet = me.isUnderLiquid(Materials.WATER);
        long now = System.nanoTime();
        for (Spk s : spk.values()) {
            if (s.id == Voice.SELF) { s.known = true; s.gain = 1; s.hf = 1; s.dist = 0; continue; }
            if (now - s.last > 1_000_000_000L && s.pending.isEmpty()) continue;
            Player body = find(w, s.id);
            if (body == null) { s.known = false; s.gain = 0; continue; }
            float x = (float) body.x, y = (float) (body.bb.minY + body.getHeadHeight()), z = (float) body.z;
            double dx = x - lx, dy = y - ly, dz = z - lz;
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float g = loud(d), hf = 1, m = 0;
            if (g > 0) {
                m = walls(w, lx, ly, lz, x, y, z);
                hf = Math.max(WALL_HF_FLOOR, (float) Math.exp(-m * HF_PER_BLOCK));
                g *= Math.max(WALL_GAIN_FLOOR, (float) Math.exp(-m * GAIN_PER_BLOCK));
                if (wet) { hf *= WATER_HF; g *= WATER_GAIN; }
            }
            s.x = x; s.y = y; s.z = z; s.hf = hf; s.gain = g; s.dist = d; s.known = true;
            s.walls = m;
            me_at = String.format("%.1f %.1f %.1f", lx, ly, lz);
        }
    }

    static float loud(float d) {
        if (d >= REACH) return 0;
        float g = 1 / (1 + ROLLOFF * Math.max(0, d - FULL));
        float e = Math.max(0, Math.min(1, (d - REACH * EDGE_FROM) / (REACH * (1 - EDGE_FROM))));
        return g * (1 - e * e * (3 - 2 * e));
    }

    private static Player find(World w, int id) {
        try { for (Player p : w.players) if (p.id == id) return p; } catch (RuntimeException ignored) { }
        return null;
    }

    private static float walls(World w, double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 1) return 0;
        int steps = (int) Math.min(96, Math.ceil(len * 2));
        int lx = fl(fx), ly = fl(fy), lz = fl(fz), ex = fl(tx), ey = fl(ty), ez = fl(tz);
        float m = 0;
        int top = w.getHeightBlocks();
        for (int i = 1; i < steps; i++) {
            double k = (double) i / steps;
            int bx = fl(fx + dx * k), by = fl(fy + dy * k), bz = fl(fz + dz * k);
            if (bx == lx && by == ly && bz == lz || bx == ex && by == ey && bz == ez) continue;
            lx = bx; ly = by; lz = bz;
            if (by < 0 || by >= top) continue;
            m += muffle(w.getBlockId(bx, by, bz));
        }
        return m;
    }

    private static float muffle(int id) {
        if (id <= 0 || id >= Blocks.blocksList.length) return 0;
        Block<?> b = Blocks.blocksList[id];
        if (b == null) return 0;
        Material mat = b.getMaterial();
        if (mat == Materials.CLOTH) return 1.6F;
        if (mat == Materials.WATER || mat == Materials.LAVA) return 0.5F;
        if (mat == Materials.LEAVES) return 0.15F;
        if (mat == Materials.GLASS) return 0.3F;
        if (!b.isSolidRender()) return 0;
        return mat == Materials.WOOD ? 0.7F : 1;
    }

    private static int fl(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    public void run() {
        try {
            while (run) {
                long ctx = 0;
                try { ctx = ALC10.alcGetCurrentContext(); AL.getCapabilities(); } catch (Throwable e) { ctx = 0; }
                if (ctx == 0) {

                    problem = "Game sound is off";
                    for (Spk s : spk.values()) { s.src = 0; s.filter = 0; s.free.clear(); }
                    nap(100);
                    continue;
                }
                problem = null;
                if (ctx != efx_ctx) { efx_tried = false; efx_ctx = ctx; }
                if (!efx_tried) {
                    efx_tried = true;
                    try { efx = ALC10.alcIsExtensionPresent(ALC10.alcGetContextsDevice(ctx), "ALC_EXT_EFX"); } catch (Throwable e) { efx = false; }
                }
                long now = System.nanoTime();
                Iterator<Spk> it = spk.values().iterator();
                while (it.hasNext()) {
                    Spk s = it.next();
                    if (s.ctx != ctx) { s.src = 0; s.filter = 0; s.free.clear(); s.ctx = ctx; }
                    if (now - s.last > FORGET_NS && s.pending.isEmpty()) { release(s); it.remove(); continue; }
                    try { service(s); } catch (Throwable e) { release(s); }
                }
                try { service_hurts(ctx); } catch (Throwable e) { release_hurts(); }
                nap(5);
            }
        } finally {
            for (Spk s : spk.values()) release(s);
            release_hurts();
        }
    }

    private void service(Spk s) {
        if (s.src == 0) {
            if (s.pending.isEmpty()) return;
            s.src = AL10.alGenSources();
            AL10.alSourcef(s.src, AL10.AL_ROLLOFF_FACTOR, 0);

            AL10.alSourcei(s.src, AL10.AL_SOURCE_RELATIVE, s.id == Voice.SELF ? AL10.AL_TRUE : AL10.AL_FALSE);
            AL10.alSourcei(s.src, AL10.AL_LOOPING, AL10.AL_FALSE);
            if (efx) {
                try {
                    s.filter = EXTEfx.alGenFilters();
                    EXTEfx.alFilteri(s.filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                } catch (Throwable e) { s.filter = 0; efx = false; }
            }
        }
        int src = s.src;
        for (int done = AL10.alGetSourcei(src, AL10.AL_BUFFERS_PROCESSED); done > 0; done--) s.free.push(AL10.alSourceUnqueueBuffers(src));
        int queued = AL10.alGetSourcei(src, AL10.AL_BUFFERS_QUEUED);
        boolean playing = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
        if (!playing) s.ref_next = VoiceEcho.clock() + (long) queued * Voice.FRAME;

        boolean ready = queued > 0 || s.pending.size() >= PREBUFFER || s.ended && !s.pending.isEmpty();
        short[] pcm;
        while (ready && queued < QUEUED && (pcm = s.pending.poll()) != null) {
            int buf = s.free.isEmpty() ? AL10.alGenBuffers() : s.free.pop();
            up.clear();
            up.put(pcm);
            up.flip();
            AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, up, Voice.RATE);

            if (s.known) VoiceEcho.write(s.ref_next, pcm, Math.min(2, s.gain * vol));
            s.ref_next += pcm.length;
            AL10.alSourceQueueBuffers(src, buf);
            queued++;
            played++;
        }
        if (s.id == Voice.SELF) AL10.alSource3f(src, AL10.AL_POSITION, 0, 0, 0);
        else AL10.alSource3f(src, AL10.AL_POSITION, s.x, s.y, s.z);
        AL10.alSourcef(src, AL10.AL_GAIN, s.known ? Math.max(0, Math.min(2, s.gain * vol)) : 0);
        if (s.filter != 0) {
            EXTEfx.alFilterf(s.filter, EXTEfx.AL_LOWPASS_GAIN, 1);
            EXTEfx.alFilterf(s.filter, EXTEfx.AL_LOWPASS_GAINHF, Math.max(0, Math.min(1, s.hf)));
            AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, s.filter);
        }
        if (queued > 0 && !playing) { starts++; AL10.alSourcePlay(src); }
    }

    private static void release(Spk s) {
        try {
            if (s.src != 0 && ALC10.alcGetCurrentContext() == s.ctx) {
                AL10.alSourceStop(s.src);
                for (int done = AL10.alGetSourcei(s.src, AL10.AL_BUFFERS_PROCESSED); done > 0; done--) s.free.push(AL10.alSourceUnqueueBuffers(s.src));
                AL10.alDeleteSources(s.src);
                while (!s.free.isEmpty()) AL10.alDeleteBuffers(s.free.pop());
                if (s.filter != 0) EXTEfx.alDeleteFilters(s.filter);
            }
        } catch (Throwable ignored) { }
        s.src = 0;
        s.filter = 0;
        s.free.clear();
    }

    static final class Hurt { short[] pcm; float pitch, x, y, z, gain; boolean rel; }
    private final ConcurrentLinkedQueue<Hurt> hurt_q = new ConcurrentLinkedQueue<>();
    private final java.util.IdentityHashMap<short[], Integer> hurt_bufs = new java.util.IdentityHashMap<>();
    private final java.util.ArrayList<Integer> hurt_srcs = new java.util.ArrayList<>();
    private long hurt_ctx;
    private volatile long hurt_until;

    void hurt(short[] pcm, float pitch, float x, float y, float z, float gain, boolean rel) {
        if (hurt_q.size() > 16) return;
        Hurt h = new Hurt();
        h.pcm = pcm; h.pitch = Math.max(0.25F, Math.min(4, pitch)); h.x = x; h.y = y; h.z = z; h.gain = Math.min(2, gain); h.rel = rel;
        hurt_q.add(h);
        hurt_until = System.nanoTime() + 1_000_000_000L + (long) (pcm.length / (Voice.RATE * h.pitch) * 1e9);
    }

    boolean hurts_busy() { return !hurt_q.isEmpty() || System.nanoTime() < hurt_until; }

    private void service_hurts(long ctx) {
        if (ctx != hurt_ctx) { hurt_bufs.clear(); hurt_srcs.clear(); hurt_ctx = ctx; }
        for (Iterator<Integer> it = hurt_srcs.iterator(); it.hasNext(); ) {
            int src = it.next();
            if (AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) { AL10.alDeleteSources(src); it.remove(); }
        }
        if (hurt_srcs.isEmpty() && hurt_bufs.size() > 8) { for (int b : hurt_bufs.values()) AL10.alDeleteBuffers(b); hurt_bufs.clear(); }
        Hurt h;
        while ((h = hurt_q.poll()) != null) {
            if (hurt_srcs.size() >= 8) continue;
            Integer buf = hurt_bufs.get(h.pcm);
            if (buf == null) {
                buf = AL10.alGenBuffers();
                ShortBuffer sb = BufferUtils.createShortBuffer(h.pcm.length);
                sb.put(h.pcm).flip();
                AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, sb, Voice.RATE);
                hurt_bufs.put(h.pcm, buf);
            }
            int src = AL10.alGenSources();
            AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0);
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, h.rel ? AL10.AL_TRUE : AL10.AL_FALSE);
            AL10.alSource3f(src, AL10.AL_POSITION, h.x, h.y, h.z);
            AL10.alSourcef(src, AL10.AL_PITCH, h.pitch);
            AL10.alSourcef(src, AL10.AL_GAIN, h.gain);
            AL10.alSourcei(src, AL10.AL_BUFFER, buf);
            AL10.alSourcePlay(src);
            hurt_srcs.add(src);
            hurt_played++;

            if (Voice.ref_wanted()) {
                int n = (int) (h.pcm.length / h.pitch);
                float[] r = new float[n];
                for (int i = 0; i < n; i++) { double p = i * h.pitch; int k = (int) p; float t = (float) (p - k); float a = h.pcm[k], b = k + 1 < h.pcm.length ? h.pcm[k + 1] : a; r[i] = a + (b - a) * t; }
                VoiceEcho.write(VoiceEcho.clock(), r, h.gain, false);
            }
        }
    }
    volatile int hurt_played;

    private void release_hurts() {
        try {
            if (ALC10.alcGetCurrentContext() == hurt_ctx) {
                for (int s : hurt_srcs) { AL10.alSourceStop(s); AL10.alDeleteSources(s); }
                for (int b : hurt_bufs.values()) AL10.alDeleteBuffers(b);
            }
        } catch (Throwable ignored) { }
        hurt_srcs.clear();
        hurt_bufs.clear();
    }

    private static void nap(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
