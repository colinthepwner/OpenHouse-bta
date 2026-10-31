package openhouse.mod;

import openhouse.Joiner;
import openhouse.Update;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScreenMainMenu;
import net.minecraft.client.gui.server.ScreenSelectServer;
import net.minecraft.client.render.texture.stitcher.TextureRegistry;
import net.minecraft.core.lang.I18n;
import net.minecraft.core.data.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpenHouseMod implements ClientModInitializer {
    public static final String MOD_ID = "openhouse";
    public static final Logger LOG = LoggerFactory.getLogger("OpenHouse");

    private static Joiner joiner;

    static String typed;

    public static volatile String singlePlayerWorld;

    private static volatile String autoJoin = System.getProperty("openhouse.autojoin");
    public static final String PACK = System.getProperty("openhouse.pack", "");
    private static volatile ScreenMainMenu titlePending;
    private static boolean updateOffered;

    public static void titleScreen(ScreenMainMenu menu) {
        titlePending = menu;
    }

    public static void clientTick(Minecraft mc) {
        ScreenMainMenu menu = titlePending;
        if (menu != null && mc.currentScreen == menu) {
            titlePending = null;
            LocalServer ls = LocalServer.current();
            if (ls != null && ls.state == LocalServer.State.READY && mc.currentWorld == null) {

                ls.stop();
                mc.displayScreen(new ScreenOpenToFriends(ls, true));
            } else if (autoJoin != null) {
                String code = autoJoin;
                autoJoin = null;
                mc.displayScreen(new ScreenRoomJoin(new ScreenSelectServer(menu), code));
            }
        } else if (menu != null && !(mc.currentScreen instanceof ScreenMainMenu)) {
            titlePending = null;
        }

        String discordCode = OpenHouseDiscord.pendingJoin;
        if (discordCode != null && mc.currentWorld == null && (mc.currentScreen instanceof ScreenMainMenu || mc.currentScreen instanceof ScreenSelectServer)) {
            OpenHouseDiscord.pendingJoin = null;
            Object parent = mc.currentScreen instanceof ScreenSelectServer ? mc.currentScreen : new ScreenSelectServer(mc.currentScreen);
            mc.displayScreen(new ScreenRoomJoin((ScreenSelectServer) parent, discordCode));
        }

        if (mc.currentScreen instanceof ScreenMainMenu) {
            if (PACK.isEmpty()) Updater.checkInBackground();
            Update.Release r = Updater.latest;
            if (!updateOffered && r != null && r != Updater.NONE && r.newer()) {
                updateOffered = true;
                mc.displayScreen(new ScreenUpdate(mc.currentScreen, r, null));
            }
        }

        if (mc.currentWorld != null && !mc.isMultiplayerWorld()) LocalServer.prefetch();

        LocalServer ls = LocalServer.current();

        if (ls != null && ls.crash != null && !ls.crashShown && mc.currentScreen instanceof net.minecraft.client.gui.ScreenConnectFailed) {
            ls.crashShown = true;
            mc.displayScreen(new ScreenOpenToFriends(ls, false));
        }
        if (ls != null && ls.code != null && !ls.announced && LocalServer.hosting() && mc.thePlayer != null && mc.isMultiplayerWorld() && mc.hudIngame != null) {
            ls.announced = true;
            mc.hudIngame.addChatMessage(I18n.getInstance().translateKeyAndFormat("gui.openhouse.lan.announce", ls.code));

            if (!ls.leftOut.isEmpty())
                mc.hudIngame.addChatMessage(I18n.getInstance().translateKeyAndFormat("gui.openhouse.lan.leftout", String.join(", ", ls.leftOut)));
        }
    }

    @Override
    public void onInitializeClient() {

        TextureRegistry.excludedNamespaces.add(MOD_ID);
        Registries.NAMESPACES.register(MOD_ID, MOD_ID);
        typed = settings().getProperty("lastCode", "");
    }

    public static String worldFolder(Minecraft mc) {
        try {
            File data = mc.currentWorld.getLevelStorage().getDataFile("openhouse");
            File world = data.getParentFile().getParentFile();
            if (new File(world, "level.dat").isFile()) return world.getName();
        } catch (Throwable ignored) { }
        String last = singlePlayerWorld;
        if (last != null && new File(FabricLoader.getInstance().getGameDir().toFile(), "saves" + File.separator + last + File.separator + "level.dat").isFile()) return last;
        return null;
    }

    public static File dataDir() {
        return new File(FabricLoader.getInstance().getGameDir().toFile(), MOD_ID);
    }

    public static synchronized Joiner joinerFor(String code) {
        if (joiner != null && joiner.code.equals(code) && joiner.alive()) {
            joiner.probe();
            return joiner;
        }
        if (joiner != null) joiner.stop();
        joiner = new Joiner(dataDir(), code, s -> LOG.info("[room {}] {}", code, s));
        joiner.start();
        return joiner;
    }

    private static volatile String connectHost;
    private static volatile int connectPort;

    public static void connectingTo(String host, int port) {
        connectHost = host;
        connectPort = port;
    }

    public static boolean connectingToRoom() {
        String h = connectHost;
        Joiner j = joiner;
        if (h == null || j == null || j.port <= 0 || connectPort != j.port) return false;
        h = h.trim().toLowerCase(java.util.Locale.ROOT);
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || h.equals("[::1]");
    }

    public static final String LAN_MARK = " (OpenHouse)";
    public static boolean isRoomEntry(String name, String motd, String address) {
        if ((name != null && name.endsWith(LAN_MARK)) || (motd != null && motd.endsWith(LAN_MARK))) return true;
        if (address == null) return false;
        String a = address.trim().toLowerCase(java.util.Locale.ROOT);
        int c = a.lastIndexOf(':');
        if (c <= 0) return false;
        String host = a.substring(0, c);
        int port;
        try { port = Integer.parseInt(a.substring(c + 1)); } catch (NumberFormatException e) { return false; }
        boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
        Joiner j = joiner;
        return loopback && j != null && j.port > 0 && port == j.port;
    }

    private static Properties settings() {
        Properties p = new Properties();
        File f = new File(dataDir(), "openhouse.properties");
        if (f.isFile()) try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception ignored) { }
        return p;
    }

    static void rememberCode(String code) {
        Properties p = settings();
        if (code.equals(p.getProperty("lastCode"))) return;
        p.setProperty("lastCode", code);
        File d = dataDir();
        d.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(d, "openhouse.properties"))) {
            p.store(out, "OpenHouse");
        } catch (Exception e) {
            LOG.warn("can't save the last room code: {}", e.toString());
        }
    }
}
