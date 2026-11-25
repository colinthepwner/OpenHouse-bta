package openhouse.mod.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.data.gamerule.GameRuleBoolean;
import net.minecraft.core.entity.monster.MobMonster;
import net.minecraft.core.entity.monster.MobSpider;
import net.minecraft.core.net.handler.PacketHandler;
import net.minecraft.core.net.packet.PacketCustomPayload;
import net.minecraft.core.world.World;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;
import openhouse.mod.voice.VoiceWire;
import org.joml.primitives.AABBd;

public final class VoiceRelay {
    private VoiceRelay() { }

    public static final double RANGE = 56;

    static final int MAX_FRAME = 1500;

    static final double SIGHT = 16, HEAR_MAX = SIGHT * 1.3;
    static final long HEAR_EVERY_MS = 400;
    static final boolean LOG = Boolean.getBoolean("openhouse.voicelog");

    static final GameRuleBoolean MOBS_HEAR = VoiceWire.MOBS_HEAR;

    private static final Set<PacketHandler> hello = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final Set<PacketHandler> known = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    public static volatile boolean on = !"false".equals(System.getProperty("openhouse.voice"));

    public static void install() {
        VoiceWire.server = VoiceRelay::got;
        VoiceWire.hurt = (p, pitch, death) -> p instanceof PlayerServer && hurt((PlayerServer) p, pitch, death);
        load_mutes();
        load_locator();
        AfkServer.load();
    }

    static final java.util.Map<PacketHandler, long[]> rate = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    static boolean allowed(PacketHandler from, byte kind) {
        long now = System.currentTimeMillis();
        long[] r = rate.computeIfAbsent(from, k -> new long[6]);
        synchronized (r) {
            if (now / 1000 != r[0]) { r[0] = now / 1000; r[1] = 0; r[2] = 0; }
            if (now / 60000 != r[3]) { r[3] = now / 60000; r[4] = 0; }
            if (kind == VoiceWire.UP) return ++r[1] <= 75;
            if (kind == VoiceWire.DIAG) { if (now - r[5] < 10000) return false; r[5] = now; return true; }
            if (kind == VoiceWire.HURT_SET || kind == VoiceWire.HURT_ADD || kind == VoiceWire.DEATH_SET) return ++r[4] <= 8 && ++r[2] <= 10;
            return ++r[2] <= 20;
        }
    }

    static void got(PacketHandler from, byte[] d) {
        if (d.length == 0) return;
        if (!allowed(from, d[0])) return;
        MinecraftServer srv = MinecraftServer.getInstance();
        if (srv == null || srv.playerList == null) return;

        if (d[0] == VoiceWire.DIAG) { PlayerServer me = find(srv, from); ServerDiag.got(me == null ? "?" : me.username, d); return; }
        if (!on) return;
        if (d[0] == VoiceWire.HURT_SET) { hurt_set(srv, from, d); return; }
        if (d[0] == VoiceWire.HURT_ADD) { hurt_add(srv, from, d); return; }
        if (d[0] == VoiceWire.DEATH_SET) { death_set(srv, from, d); return; }
        if (d[0] == VoiceWire.AFK) { AfkServer.got(from, d); return; }
        if (d[0] == VoiceWire.HELLO) {

            if (d.length > 1 && d[1] == 0) hello.remove(from); else hello.add(from);
            known.add(from);
            AfkServer.hello();
            PlayerServer me = find(srv, from);
            if (me != null) {

                ServerRoom room = ServerRoom.get();
                String code = room == null || room.liveCode == null ? "" : room.liveCode;
                byte[] cb = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                byte[] h = new byte[3 + Math.min(32, cb.length)];
                h[0] = VoiceWire.HELLO; h[1] = (byte) VoiceWire.PROTO; h[2] = (byte) (h.length - 3);
                System.arraycopy(cb, 0, h, 3, h.length - 3);
                send(me, h);
                send(me, new byte[] { VoiceWire.STATE, state_of(me.username) });
                byte[] keys = openhouse.mod.MsgKeys.wire(VoiceWire.MSG_KEYS);
                if (keys != null) send(me, keys);
            }
            return;
        }
        if (d[0] != VoiceWire.UP || d.length > MAX_FRAME || !hello.contains(from)) return;
        List<PlayerServer> all = players(srv);
        PlayerServer me = null;
        for (PlayerServer p : all) if (p.playerNetServerHandler == from) { me = p; break; }
        if (me == null) return;
        boolean end = d.length < 3;
        int loud = end ? 0 : d[1] & 0xFF;
        boolean hear = !end && MOBS_HEAR_on(me);
        if (hear) heard(me, loud);
        byte[] out;
        if (end) out = new byte[5];
        else {
            out = new byte[d.length + 4];
            out[5] = (byte) loud;
            System.arraycopy(d, 2, out, 6, d.length - 2);
        }
        out[0] = VoiceWire.DOWN;
        int id = me.id;
        out[1] = (byte) (id >>> 24); out[2] = (byte) (id >>> 16); out[3] = (byte) (id >>> 8); out[4] = (byte) id;
        PacketCustomPayload pkt = new PacketCustomPayload(VoiceWire.CHANNEL, out);
        String who = me.username;
        boolean gagged = muted_for_all(who);
        double r2 = RANGE * RANGE;
        for (PlayerServer p : all) {
            if (p == me || p.dimension != me.dimension || p.playerNetServerHandler == null || !hello.contains(p.playerNetServerHandler)) continue;

            if (!end && (gagged || muted(p.username, who))) continue;
            double dx = p.x - me.x, dy = p.y - me.y, dz = p.z - me.z;
            if (!end && dx * dx + dy * dy + dz * dz > r2) continue;
            p.playerNetServerHandler.netManager.addToSendQueue(pkt);
        }
    }

    static boolean knows(PacketHandler h) { return h != null && known.contains(h); }

    static void send(PlayerServer p, byte[] d) {
        if (p.playerNetServerHandler != null) p.playerNetServerHandler.netManager.addToSendQueue(new PacketCustomPayload(VoiceWire.CHANNEL, d));
    }

    private static PlayerServer find(MinecraftServer srv, PacketHandler h) {
        for (PlayerServer p : players(srv)) if (p.playerNetServerHandler == h) return p;
        return null;
    }

    static List<PlayerServer> players(MinecraftServer srv) {
        for (int i = 0; i < 3; i++) {
            try { return new ArrayList<>(srv.playerList.playerEntities); } catch (RuntimeException ignored) { }
        }
        return Collections.emptyList();
    }

    private static final Map<String, Set<String>> mutes = new ConcurrentHashMap<>();
    static final String ALL = "*";

    private static String key(String n) { return n == null ? "" : n.toLowerCase(java.util.Locale.ROOT); }

    static boolean muted(String listener, String speaker) {
        Set<String> s = mutes.get(key(listener));
        return s != null && s.contains(key(speaker));
    }

    static boolean muted_for_all(String speaker) { return muted(ALL, speaker); }

    static boolean set_mute(String listener, String speaker, boolean m) {
        String l = key(listener), sp = key(speaker);
        boolean ch;
        if (m) ch = mutes.computeIfAbsent(l, k -> ConcurrentHashMap.newKeySet()).add(sp);
        else { Set<String> s = mutes.get(l); ch = s != null && s.remove(sp); if (s != null && s.isEmpty()) mutes.remove(l); }
        if (ch) save_mutes();
        return ch;
    }

    static Set<String> muted_by(String listener) {
        Set<String> s = mutes.get(key(listener));
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    static void tell_state(MinecraftServer srv, String name) {
        for (PlayerServer p : players(srv)) if (p.username != null && p.username.equalsIgnoreCase(name) && known.contains(p.playerNetServerHandler))
            send(p, new byte[] { VoiceWire.STATE, state_of(name) });
    }

    static byte state_of(String name) {
        Boolean loc = locator.get(key(name));
        return (byte) ((muted_for_all(name) ? 1 : 0) | (Boolean.FALSE.equals(loc) ? 2 : 0) | (Boolean.TRUE.equals(loc) ? 4 : 0));
    }

    private static final Map<String, Boolean> locator = new ConcurrentHashMap<>();

    static Boolean locator_of(String name) { return locator.get(key(name)); }

    static Map<String, Boolean> locator_all() { return new java.util.TreeMap<>(locator); }

    static boolean set_locator(String name, Boolean v) {
        String k = key(name);
        Boolean was = v == null ? locator.remove(k) : locator.put(k, v);
        boolean ch = v == null ? was != null : !v.equals(was);
        if (ch) save_locator();
        return ch;
    }

    private static File locator_file() { return new File("openhouse", "locator.properties"); }

    private static void load_locator() {
        File f = locator_file();
        if (!f.isFile()) return;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception e) { return; }
        for (String n : p.stringPropertyNames()) locator.put(key(n), !"off".equals(p.getProperty(n, "").trim()));
    }

    private static synchronized void save_locator() {
        Properties p = new Properties();
        for (Map.Entry<String, Boolean> e : locator.entrySet()) p.setProperty(e.getKey(), e.getValue() ? "on" : "off");
        File f = locator_file();
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { p.store(out, "OpenHouse player locator, per player: on/off (missing = the showPlayerLocator game rule)"); } catch (Exception ignored) { }
    }

    private static File mute_file() { return new File("openhouse", "voice-mutes.properties"); }

    private static void load_mutes() {
        File f = mute_file();
        if (!f.isFile()) return;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception e) { return; }
        for (String l : p.stringPropertyNames()) {
            Set<String> s = ConcurrentHashMap.newKeySet();
            for (String sp : p.getProperty(l, "").split(",")) if (!sp.trim().isEmpty()) s.add(key(sp.trim()));
            if (!s.isEmpty()) mutes.put(key(l), s);
        }
    }

    private static synchronized void save_mutes() {
        Properties p = new Properties();
        for (Map.Entry<String, Set<String>> e : mutes.entrySet()) p.setProperty(e.getKey(), String.join(",", e.getValue()));
        File f = mute_file();
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { p.store(out, "OpenHouse voice mutes: listener=speakers they don't hear (* = everyone)"); } catch (Exception ignored) { }
    }

    static final class Heard { PlayerServer who; double reach; }
    private static final ConcurrentLinkedQueue<Heard> heard_q = new ConcurrentLinkedQueue<>();
    private static final Map<String, Long> heard_at = new ConcurrentHashMap<>();

    private static boolean MOBS_HEAR_on(PlayerServer p) {
        try {
            World w = p.world;
            return w != null && Boolean.TRUE.equals(w.getLevelData().getGameRules().getValue(MOBS_HEAR));
        } catch (Throwable t) { return false; }
    }

    static double reach(int loud) {
        double db = loud * 60.0 / 255 - 60;
        return RANGE * Math.max(0, Math.min(1, (db + 50) / 35));
    }

    private static void heard(PlayerServer p, int loud) {
        long now = System.currentTimeMillis();
        Long last = heard_at.get(p.username);
        if (last != null && now - last < HEAR_EVERY_MS) return;
        heard_at.put(p.username, now);
        Heard h = new Heard();
        h.who = p;
        h.reach = reach(loud);
        heard_q.add(h);
    }

    static final byte[] BEAT = { VoiceWire.BEAT };

    public static void tick() {
        try { MinecraftServer s = MinecraftServer.getInstance(); if (s != null && s.playerList != null) AfkServer.tick(s); } catch (Throwable ignored) { }

        try {
            MinecraftServer s = MinecraftServer.getInstance();
            if (s != null && s.playerList != null) for (PlayerServer p : players(s)) if (known.contains(p.playerNetServerHandler)) send(p, BEAT);
        } catch (Throwable ignored) { }
        Heard h;
        while ((h = heard_q.poll()) != null) {
            PlayerServer p = h.who;
            double r = Math.min(h.reach, HEAR_MAX);
            if (r < 1 || p.world == null || !p.isAlive() || p.getGamemode() == null || !p.getGamemode().hasHostileMobs()) {
                continue;
            }
            List<MobMonster> mobs;
            try { mobs = p.world.getEntitiesWithinAABB(MobMonster.class, new AABBd(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r)); }
            catch (Throwable t) { if (LOG) ServerRoom.LOG.info("[voice] mob search failed: {}", t.toString()); continue; }
            if (LOG) ServerRoom.LOG.info("[voice] {} talking, carries {} blocks, {} monsters in reach", p.username, Math.round(h.reach), mobs.size());
            for (MobMonster m : mobs) {
                if (m.getTarget() != null || !m.isAlive()) continue;
                if (m.distanceTo(p) > r) continue;

                if (m instanceof MobSpider && m.calcBrightness(1.0F) >= 0.5F) continue;
                m.setTarget(p);
                if (LOG) ServerRoom.LOG.info("[voice] {} heard {} ({} blocks, reach {})", m.getClass().getSimpleName(), p.username, Math.round(m.distanceTo(p)), Math.round(r));
            }
        }
    }

    private static final Map<String, byte[][]> hurt_clips = new ConcurrentHashMap<>();
    private static final Map<String, int[]> hurt_hash = new ConcurrentHashMap<>();

    private static final Map<PacketHandler, Map<Integer, Integer>> hurt_sent = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Set<PacketHandler> hurt_ok = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static void hurt_set(MinecraftServer srv, PacketHandler from, byte[] d) {
        hurt_ok.add(from);
        PlayerServer me = find(srv, from);
        if (LOG) ServerRoom.LOG.info("[voice] hurt sound from {}: {} bytes", me == null ? "?" : me.username, d.length - 1);
        if (me == null || me.username == null) return;
        String k = key(me.username);
        if (d.length < 2 || d[1] == 0) { hurt_clips.remove(k); hurt_hash.remove(k); return; }
        if (d.length - 1 > VoiceWire.CLIP_MAX || VoiceWire.clip_frames(d, 1) == null) return;
        byte[][] c = new byte[VoiceWire.HURT_MAX][];
        int[] h = new int[VoiceWire.HURT_MAX];
        c[0] = java.util.Arrays.copyOfRange(d, 1, d.length);
        h[0] = java.util.Arrays.hashCode(c[0]);
        hurt_hash.put(k, h);
        hurt_clips.put(k, c);
    }

    private static void hurt_add(MinecraftServer srv, PacketHandler from, byte[] d) {
        PlayerServer me = find(srv, from);
        if (me == null || me.username == null || d.length < 3) return;
        int i = d[1] & 0xFF;
        String k = key(me.username);
        byte[][] c = hurt_clips.get(k);
        int[] h = hurt_hash.get(k);
        if (c == null || h == null || i < 1 || i >= VoiceWire.HURT_MAX) return;
        if (d.length - 2 > VoiceWire.CLIP_MAX || VoiceWire.clip_frames(d, 2) == null) return;
        byte[] clip = java.util.Arrays.copyOfRange(d, 2, d.length);
        h[i] = java.util.Arrays.hashCode(clip);
        c[i] = clip;
        if (LOG) ServerRoom.LOG.info("[voice] hurt sound #{} from {}: {} bytes", i + 1, me.username, clip.length);
    }

    private static final Map<String, byte[]> death_clips = new ConcurrentHashMap<>();

    private static void death_set(MinecraftServer srv, PacketHandler from, byte[] d) {
        PlayerServer me = find(srv, from);
        if (me == null || me.username == null) return;
        String k = key(me.username);
        if (d.length < 2 || d[1] == 0) { death_clips.remove(k); return; }
        if (d.length - 1 > VoiceWire.CLIP_MAX || VoiceWire.clip_frames(d, 1) == null) return;
        death_clips.put(k, java.util.Arrays.copyOfRange(d, 1, d.length));
        if (LOG) ServerRoom.LOG.info("[voice] death sound from {}: {} bytes", me.username, d.length - 1);
    }

    static boolean hurt(PlayerServer p, float pitch, boolean death) {
        if (!on || p.username == null) return false;
        String k = key(p.username);
        byte[][] all = hurt_clips.get(k);
        int[] hs = hurt_hash.get(k);
        byte[] dc = death ? death_clips.get(k) : null;
        byte[] clip;
        int hash;
        float loud = 1;
        if (dc != null) { clip = dc; hash = java.util.Arrays.hashCode(dc); }
        else {
            if (all == null || hs == null) return false;

            int n = 0;
            int[] have = new int[all.length];
            for (int i = 0; i < all.length; i++) if (all[i] != null) have[n++] = i;
            if (n == 0) return false;
            int pick = have[java.util.concurrent.ThreadLocalRandom.current().nextInt(n)];
            clip = all[pick];
            hash = hs[pick];
            if (death) loud = VoiceWire.DEATH_LOUDER;
        }
        MinecraftServer srv = MinecraftServer.getInstance();
        if (srv == null) return false;
        int id = p.id;
        byte[] play = { VoiceWire.HURT_PLAY, (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id,
                (byte) (hash >>> 24), (byte) (hash >>> 16), (byte) (hash >>> 8), (byte) hash, (byte) Math.max(25, Math.min(250, Math.round(pitch * 100))) };
        if (loud != 1) { play = java.util.Arrays.copyOf(play, 11); play[10] = (byte) Math.min(250, Math.round(loud * 100)); }
        net.minecraft.core.net.packet.Packet vanilla = null;
        double y = p.y - p.heightOffset;
        for (PlayerServer q : players(srv)) {
            if (q.dimension != p.dimension || q.playerNetServerHandler == null) continue;
            double dx = q.x - p.x, dy = q.y - p.y, dz = q.z - p.z;
            if (dx * dx + dy * dy + dz * dz > 16 * 16) continue;
            PacketHandler h = q.playerNetServerHandler;
            if (LOG) ServerRoom.LOG.info("[voice] {} {}: to {} as {}", p.username, dc != null ? "died (death sound)" : death ? "died (hurt x" + loud + ")" : "hurt", q.username, hurt_ok.contains(h) ? "clip" : "plain sound");
            if (hurt_ok.contains(h)) {
                Map<Integer, Integer> got = hurt_sent.computeIfAbsent(h, x -> new ConcurrentHashMap<>());
                Integer had = got.put(id, hash);
                if (had == null || had != hash) {
                    byte[] c = new byte[9 + clip.length];
                    System.arraycopy(play, 0, c, 0, 9);
                    c[0] = VoiceWire.HURT_CLIP;
                    System.arraycopy(clip, 0, c, 9, clip.length);
                    send(q, c);
                }
                send(q, play);
            } else {
                if (vanilla == null) {
                    int sid = net.minecraft.core.sound.SoundTypes.getSoundId(p.playerConfig.hurtSound().hurtSound);
                    if (sid < 0) continue;
                    vanilla = new net.minecraft.core.net.packet.PacketPlaySoundDirect(sid, net.minecraft.core.sound.SoundCategory.ENTITY_SOUNDS, p.x, y, p.z, 1.0F, pitch);
                }
                q.playerNetServerHandler.netManager.addToSendQueue(vanilla);
            }
        }
        return true;
    }
}
