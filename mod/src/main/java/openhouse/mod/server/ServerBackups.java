package openhouse.mod.server;

import java.io.File;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.WorldServer;
import openhouse.mod.WorldGuard;

public final class ServerBackups {
    private ServerBackups() { }

    static final long EVERY = 30 * 60_000L;
    static long last = System.currentTimeMillis();
    static boolean played;
    static volatile boolean copying, resume;

    static File world(MinecraftServer s) {
        String level = "world";
        try { if (s != null && s.propertyManager != null) level = s.propertyManager.getStringProperty("level-name", "world"); } catch (Throwable ignored) { }
        return new File(System.getProperty("user.dir"), level).getAbsoluteFile();
    }

    public static void atStart(MinecraftServer s) {
        if (!ServerRoom.OPENHOST) return;
        File w = world(s);
        if (new File(w, "level.dat").isFile()) WorldGuard.backup(w, "server-start");
        last = System.currentTimeMillis();
    }

    public static void atStop(MinecraftServer s) {
        if (!ServerRoom.OPENHOST) return;
        WorldGuard.backup(world(s), "server-stop");
    }

    public static void tick(MinecraftServer s) {
        if (!ServerRoom.OPENHOST || s == null) return;
        if (resume) { resume = false; saving(s, true); return; }
        if (copying) return;
        try { if (s.playerList != null && !s.playerList.playerEntities.isEmpty()) played = true; } catch (RuntimeException ignored) { }
        long now = System.currentTimeMillis();
        if (now - last < EVERY || !played) return;
        last = now;
        played = false;
        try {
            java.lang.reflect.Method save = MinecraftServer.class.getDeclaredMethod("saveServerWorld");
            save.setAccessible(true);
            save.invoke(s);
        } catch (Throwable t) {
            ServerRoom.LOG.info("couldn't save before the backup ({}), skipping this one", t.toString());
            return;
        }
        saving(s, false);
        copying = true;
        File w = world(s);
        Thread t = new Thread(() -> {
            try { WorldGuard.backup(w, "server"); }
            finally { copying = false; resume = true; }
        }, "OpenHost backup");
        t.setDaemon(true);
        t.start();
    }

    static void saving(MinecraftServer s, boolean on) {
        try {
            for (WorldServer w : s.dimensionWorlds.values()) if (w != null) w.dontSave = !on;
        } catch (Throwable t) { ServerRoom.LOG.info("couldn't turn saving {}: {}", on ? "back on" : "off", t.toString()); }
    }
}
