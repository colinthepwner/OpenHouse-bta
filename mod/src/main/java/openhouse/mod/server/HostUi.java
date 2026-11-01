package openhouse.mod.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.Properties;
import javax.swing.SwingUtilities;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.server.MinecraftServer;

public final class HostUi {
    private HostUi() { }

    public static final String KEY = "openhost-ui", THEME = "openhost-theme";

    static String launched() {
        String g = System.getProperty("openhouse.openhost.gui");
        return g == null || g.isEmpty() ? null : g;
    }

    static boolean hasConsole() { return System.getProperty("openhouse.openhost.console") != null; }

    static String theme() { return read(THEME, "dark"); }

    static void save(String mode) { write(KEY, mode); }

    static void saveTheme(String t) { write(THEME, t); }

    static void write(String key, String value) {
        MinecraftServer s = MinecraftServer.getInstance();
        if (s != null && s.propertyManager != null) {
            s.propertyManager.setProperty(key, value);
            s.propertyManager.saveProperties();
            return;
        }
        File f = new File("server.properties");
        Properties p = new Properties();
        if (f.isFile()) try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception ignored) { }
        p.setProperty(key, value);
        try (FileOutputStream out = new FileOutputStream(f)) { p.store(out, "Minecraft server properties"); } catch (Exception e) {
            ServerRoom.LOG.warn("couldn't save {} to server.properties: {}", key, e.toString());
        }
    }

    static String read(String key, String def) {
        MinecraftServer s = MinecraftServer.getInstance();
        try {
            if (s != null && s.propertyManager != null) return s.propertyManager.getStringProperty(key, def);
            Properties p = new Properties();
            File f = new File("server.properties");
            if (f.isFile()) try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
            return p.getProperty(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    static void switchTo(String mode, CommandSource src) {
        mode = mode.toLowerCase(Locale.ROOT);
        save(mode);
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            tell(src, "Saved " + KEY + "=" + mode + " - this machine has no screen, so OpenHost runs in console mode anyway.");
            return;
        }
        MinecraftServer s = MinecraftServer.getInstance();
        switch (mode) {
            case "modern":
                closeClassic();
                GuiLog.enabled = false;
                HostWindow.open();
                tell(src, "Switched to the modern window.");
                break;
            case "classic":
                if (s == null) { tell(src, "Saved - the classic window opens from the next start."); break; }
                HostWindow.close();
                GuiLog.installNow();
                GuiLog.enabled = true;
                SwingUtilities.invokeLater(() -> {
                    net.minecraft.server.gui.ServerGui.initGui(s);
                    ServerRoom.windowTitle(ServerRoom.get().liveCode != null ? "room code " + ServerRoom.get().liveCode : null);
                });
                tell(src, "Switched to the classic window. Type \"oh ui modern\" in it to come back.");
                break;
            case "console":
                if (hasConsole()) {
                    HostWindow.close();
                    closeClassic();
                    tell(src, "Window closed - the console is all there is now (\"oh ui modern\" brings it back).");
                } else {
                    tell(src, "Saved - the next start runs in console mode. (No console to type into right now, so the window stays.)");
                }
                break;
            default:
                tell(src, "Pick modern, classic or console.");
        }
    }

    static void closeClassic() {
        SwingUtilities.invokeLater(() -> {
            HostWindow w = HostWindow.current;
            for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                if (w != null && f == w.frame) continue;
                String t = f.getTitle();
                if (f.isDisplayable() && t != null && (t.startsWith("OpenHost") || t.equals("Minecraft server"))) f.dispose();
            }
        });
    }

    static void tell(CommandSource src, String msg) {
        if (src != null) src.sendMessage(msg);
        else ServerRoom.LOG.info(msg);
    }
}
