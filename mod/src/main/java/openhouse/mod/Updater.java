package openhouse.mod;

import openhouse.Rtc;
import openhouse.Update;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class Updater {
    private Updater() { }

    static final Update.Release NONE = new Update.Release(null, null, null, null, 0);
    static volatile Update.Release latest;
    private static volatile boolean started;

    static void checkInBackground() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            try {
                latest = check();
            } catch (Exception e) {
                OpenHouseMod.LOG.info("couldn't check for an OpenHouse update: {}", e.toString());
                latest = NONE;
            }
        }, "openhouse update check");
        t.setDaemon(true);
        t.start();
    }

    static String gameVersion() {
        return Update.gameVersion(FabricLoader.getInstance().getModContainer(OpenHouseMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(""));
    }

    public static Update.Release check() throws IOException {
        return Update.latestMod(gameVersion());
    }

    public static File download(Update.Release r, Update.Progress p) throws IOException {
        return Update.download(r, new File(OpenHouseMod.dataDir(), "update"), p);
    }

    @SuppressWarnings("unused") private static Process swap;

    public static void install(File newJar, String autojoin) throws IOException {
        File self = Rtc.self();
        if (!self.isFile()) throw new IOException("this game isn't running OpenHouse from a jar (development run)");

        List<String> folders = new ArrayList<>();
        folders.add(self.getParentFile().getAbsolutePath());
        String home = System.getProperty("openhouse.home");
        File homeDir = home != null && !home.isEmpty() ? new File(home) : new File(FabricLoader.getInstance().getGameDir().toFile(), "mods");
        if (!homeDir.getAbsoluteFile().equals(self.getParentFile().getAbsoluteFile())) folders.add(homeDir.getAbsolutePath());
        swap = Update.startSwap(self, newJar, OpenHouseMod.dataDir(), folders, Relaunch.command(null, autojoin),
                new File(System.getProperty("user.dir")));
        OpenHouseMod.LOG.info("updating OpenHouse to {} - restarting", newJar.getName());
    }
}
