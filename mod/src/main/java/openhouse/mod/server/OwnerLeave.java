package openhouse.mod.server;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;

public final class OwnerLeave {
    private OwnerLeave() { }

    static final String OWNER = System.getProperty("openhouse.openhost.owner", "");
    static final long GRACE = 45_000L;
    static boolean seen;
    static long emptySince;
    static boolean stopping;
    static int t;

    public static void tick(MinecraftServer s) {
        if (OWNER.isEmpty() || stopping || s == null || ++t % 20 != 0) return;
        List<PlayerServer> on;
        try { on = new ArrayList<>(s.playerList.playerEntities); } catch (RuntimeException e) { return; }
        for (PlayerServer p : on) if (OWNER.equalsIgnoreCase(p.username)) seen = true;
        if (!on.isEmpty()) { emptySince = 0; return; }
        if (!seen) return;
        long now = System.currentTimeMillis();
        if (emptySince == 0) { emptySince = now; return; }
        if (now - emptySince < GRACE) return;
        stopping = true;
        ServerRoom.LOG.info("{} left and nobody else is on - stopping (this OpenHost was started from their game)", OWNER);

        try {
            File f = new File(System.getProperty("user.dir"), "openhouse/running.txt");
            if (f.isFile()) {
                String[] w = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim().split(" ");
                java.nio.file.Files.write(f.toPath(), ("stopping " + (w.length > 1 ? w[1] : "0") + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
        s.initiateShutdown();
    }
}
