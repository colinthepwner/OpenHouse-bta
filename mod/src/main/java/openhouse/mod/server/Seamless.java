package openhouse.mod.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.net.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.net.PlayerList;
import net.minecraft.server.world.ServerPlayerController;

public final class Seamless {
    private Seamless() { }

    static final long GRACE = 45_000L;

    private static final class Leave { final long due; final Packet p; Leave(long d, Packet p) { due = d; this.p = p; } }
    private static final Map<String, Leave> leaving = new LinkedHashMap<>();

    static String who(Packet p) {
        try {
            Object a = p.getClass().getField("args").get(p);
            if (a instanceof String[] && ((String[]) a).length > 0) return ((String[]) a)[0];
        } catch (Throwable ignored) { }
        try {
            Object m = p.getClass().getField("message").get(p);
            if (m instanceof String) {
                String t = ((String) m).replaceAll("\u00a7.", "").trim();
                for (String end : new String[] { " left the game.", " joined the game." }) if (t.endsWith(end)) return t.substring(0, t.length() - end.length());
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static void lost(PlayerList list, Packet p) {
        String n = who(p);
        if (n == null) { list.sendPacketToAllPlayers(p); return; }
        synchronized (leaving) { leaving.put(n, new Leave(System.currentTimeMillis() + GRACE, p)); }
    }

    public static void joined(PlayerList list, Packet p) {
        String n = who(p);
        synchronized (leaving) {
            if (n != null && leaving.remove(n) != null) { ServerRoom.LOG.info("{} came back within {} s - no left/joined messages", n, GRACE / 1000); return; }
        }
        list.sendPacketToAllPlayers(p);
    }

    private static final class Dig {
        final ServerPlayerController c; final int x, y, z; final Object side; final long until;
        Dig(ServerPlayerController c, int x, int y, int z, Object side) { this.c = c; this.x = x; this.y = y; this.z = z; this.side = side; until = System.currentTimeMillis() + 15_000; }
    }
    private static final List<Dig> digs = new ArrayList<>();

    public static void finishDig(ServerPlayerController c, int x, int y, int z, net.minecraft.core.util.helper.Side side) {
        if (c == null) return;
        try {
            if (c.destroyBlock(x, y, z, side)) return;
        } catch (Throwable t) { return; }
        synchronized (digs) { digs.add(new Dig(c, x, y, z, side)); }
        ServerRoom.LOG.info("finishing a block a dropped player was still mining at {} {} {}", x, y, z);
    }

    static final long TEST_DROP;
    static { long v = 0; try { v = Long.parseLong(System.getenv().getOrDefault("OPENHOUSE_TEST_DROPSERVER", "0").trim()); } catch (RuntimeException ignored) { } TEST_DROP = v; }
    static final Map<String, Long> came = new LinkedHashMap<>();
    static boolean test_dropped;
    static void testDrop(MinecraftServer s, long now) {
        if (TEST_DROP <= 0 || test_dropped) return;
        java.io.File mark = new java.io.File("openhouse", "test-dropped");
        if (mark.exists()) { test_dropped = true; return; }
        for (net.minecraft.server.entity.player.PlayerServer p : new ArrayList<>(s.playerList.playerEntities)) {
            Long t = came.get(p.username);
            if (t == null) { came.put(p.username, now); continue; }
            if (now - t < TEST_DROP) continue;
            test_dropped = true;
            try { mark.getParentFile().mkdirs(); mark.createNewFile(); } catch (Exception ignored) { }
            ServerRoom.LOG.info("TEST: dropping {}'s connection (OPENHOUSE_TEST_DROPSERVER)", p.username);
            try { p.playerNetServerHandler.netManager.networkShutdown("disconnect.endOfStream", new Object[0]); } catch (Throwable e) { ServerRoom.LOG.info("TEST drop failed: {}", e.toString()); }
            return;
        }
    }

    public static void tick(MinecraftServer s) {
        long now = System.currentTimeMillis();
        try { testDrop(s, now); } catch (Throwable ignored) { }
        List<Packet> say = new ArrayList<>();
        synchronized (leaving) {
            for (Iterator<Leave> i = leaving.values().iterator(); i.hasNext(); ) { Leave l = i.next(); if (now >= l.due) { say.add(l.p); i.remove(); } }
        }
        for (Packet p : say) try { s.playerList.sendPacketToAllPlayers(p); } catch (Throwable ignored) { }
        synchronized (digs) {
            for (Iterator<Dig> i = digs.iterator(); i.hasNext(); ) {
                Dig d = i.next();
                try {
                    d.c.tick();
                    Object w = d.c.player == null ? null : d.c.player.world;
                    boolean gone = w instanceof net.minecraft.core.world.World && ((net.minecraft.core.world.World) w).getBlockId(d.x, d.y, d.z) == 0;
                    if (gone || now > d.until) i.remove();
                } catch (Throwable t) { i.remove(); }
            }
        }
    }
}
