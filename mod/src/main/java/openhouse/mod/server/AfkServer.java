package openhouse.mod.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.MobPathfinder;
import net.minecraft.core.net.handler.PacketHandler;
import net.minecraft.core.world.World;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;
import openhouse.mod.voice.VoiceWire;
import org.joml.primitives.AABBd;

public final class AfkServer {
    private AfkServer() { }

    private static final Map<PacketHandler, Long> afk = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    static volatile int idle_s = 60, kick_s = 120;
    private static long beat_at;
    private static boolean dirty;

    static void got(PacketHandler from, byte[] d) {
        if (d.length < 2) return;
        boolean on = d[1] != 0;
        boolean changed;
        synchronized (afk) {
            Long was = afk.get(from);
            changed = on ? was == null || was == 0 : was != null && was != 0;
            if (changed) { afk.put(from, on ? System.currentTimeMillis() : 0L); dirty = true; }
            else if (was == null) afk.put(from, 0L);
        }
        if (changed) {
            MinecraftServer s = MinecraftServer.getInstance();
            for (PlayerServer p : s == null ? java.util.Collections.<PlayerServer>emptyList() : VoiceRelay.players(s))
                if (p.playerNetServerHandler == from) ServerRoom.LOG.info("{} is {}", p.username, on ? "AFK" : "back");
        }
    }

    static void hello() { dirty = true; }

    public static boolean is_afk(Object p) {
        if (!(p instanceof PlayerServer)) return false;
        Long t = afk.get(((PlayerServer) p).playerNetServerHandler);
        return t != null && t != 0;
    }

    public static boolean ignored(Object p) {
        if (!is_afk(p)) return false;
        World w = ((PlayerServer) p).world;
        return w == null || !Boolean.FALSE.equals(w.getLevelData().getGameRules().getValue(VoiceWire.AFK_MOBS));
    }

    public static void tick(MinecraftServer srv) {
        long now = System.currentTimeMillis();
        if (!dirty && now - beat_at < 5000) { if (now - beat_at >= 1000) kicks(srv, now); return; }
        dirty = false;
        beat_at = now;
        kicks(srv, now);
        List<PlayerServer> all = VoiceRelay.players(srv);
        java.util.List<int[]> rows = new java.util.ArrayList<>();
        for (PlayerServer p : all) {
            Long t = afk.get(p.playerNetServerHandler);
            if (t != null && t != 0) rows.add(new int[] { p.id, (int) Math.min(Integer.MAX_VALUE, now - t) });
        }
        World w = srv.getDimensionWorld(0);
        boolean fade = w == null || !Boolean.FALSE.equals(w.getLevelData().getGameRules().getValue(VoiceWire.AFK_FADE));
        byte[] d = new byte[8 + rows.size() * 8];
        d[0] = VoiceWire.AFK_LIST;
        d[1] = (byte) (fade ? 1 : 0);
        int k = Math.min(65535, kick_s), i = Math.min(65535, idle_s);
        d[2] = (byte) (k >> 8); d[3] = (byte) k; d[4] = (byte) (i >> 8); d[5] = (byte) i;
        d[6] = (byte) (rows.size() >> 8); d[7] = (byte) rows.size();
        int o = 8;
        for (int[] r : rows) { put(d, o, r[0]); put(d, o + 4, r[1]); o += 8; }
        for (PlayerServer p : all) if (VoiceRelay.knows(p.playerNetServerHandler)) VoiceRelay.send(p, d);
    }

    static void put(byte[] d, int o, int v) { d[o] = (byte) (v >>> 24); d[o + 1] = (byte) (v >>> 16); d[o + 2] = (byte) (v >>> 8); d[o + 3] = (byte) v; }

    private static void kicks(MinecraftServer srv, long now) {
        String host = ServerRoom.LAN ? ServerRoom.lanHostName : null;
        for (PlayerServer p : VoiceRelay.players(srv)) {
            Long t = afk.get(p.playerNetServerHandler);
            if (t == null || t == 0) continue;
            if (ignored(p)) drop_targets(p);
            if (kick_s <= 0 || now - t < kick_s * 1000L || p.playerNetServerHandler == null) continue;
            if (host != null && host.equalsIgnoreCase(p.username)) continue;
            ServerRoom.LOG.info("{} was AFK for {} s: disconnected", p.username, kick_s);
            afk.remove(p.playerNetServerHandler);
            dirty = true;
            p.playerNetServerHandler.kickPlayer("You were disconnected for being AFK");
        }
    }

    private static void drop_targets(PlayerServer p) {
        if (p.world == null) return;
        try {
            for (Entity e : p.world.getEntitiesWithinAABB(MobPathfinder.class, new AABBd(p.x - 48, p.y - 48, p.z - 48, p.x + 48, p.y + 48, p.z + 48)))
                if (e instanceof MobPathfinder && ((MobPathfinder) e).getTarget() == p) ((MobPathfinder) e).setTarget(null);
        } catch (Throwable ignored) { }
    }

    private static File file() { return new File("openhouse", "afk.properties"); }

    static void load() {
        File f = file();
        if (!f.isFile()) return;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception e) { return; }
        idle_s = num(p.getProperty("afk-idle-seconds"), idle_s);
        kick_s = num(p.getProperty("afk-kick-seconds"), kick_s);
    }

    static void save() {
        Properties p = new Properties();
        p.setProperty("afk-idle-seconds", String.valueOf(idle_s));
        p.setProperty("afk-kick-seconds", String.valueOf(kick_s));
        File f = file();
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { p.store(o, "OpenHouse AFK: idle time before AFK, AFK time before kicked (0 = never)"); } catch (Exception ignored) { }
        dirty = true;
    }

    static int num(String s, int def) {
        try { return Math.max(0, Math.min(86400, Integer.parseInt(s.trim()))); } catch (Exception e) { return def; }
    }
}
