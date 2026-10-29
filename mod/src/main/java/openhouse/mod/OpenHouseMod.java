package openhouse.mod;

import openhouse.Joiner;
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

        LocalServer ls = LocalServer.current();
        if (ls != null && ls.code != null && !ls.announced && LocalServer.hosting() && mc.thePlayer != null && mc.isMultiplayerWorld() && mc.hudIngame != null) {
            ls.announced = true;
            mc.hudIngame.addChatMessage(I18n.getInstance().translateKeyAndFormat("gui.openhouse.lan.announce", ls.code));
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
