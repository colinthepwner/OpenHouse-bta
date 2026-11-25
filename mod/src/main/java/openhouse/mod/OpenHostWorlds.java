package openhouse.mod;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.world.save.ISaveFormat;
import net.minecraft.core.world.save.SaveFile;
import openhouse.OpenHostList;

public final class OpenHostWorlds {
    private OpenHostWorlds() { }

    static String label(Minecraft mc, String name) {
        String tag = " " + net.minecraft.core.net.command.TextFormatting.ORANGE + "(Hosted using OpenHost)" + net.minecraft.core.net.command.TextFormatting.RESET;
        if (name == null) name = "";
        try {
            int room = 212 - mc.font.stringWidth(tag);
            if (mc.font.stringWidth(name) > room) {
                while (name.length() > 1 && mc.font.stringWidth(name + "...") > room) name = name.substring(0, name.length() - 1);
                name = name + "...";
            }
        } catch (Throwable ignored) { }
        return name + tag;
    }

    public interface Rows { boolean openhouse$openhost(int row); }

    public static volatile OpenHostList.Server playing;

    static final Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();
    public static String nameOf(OpenHostList.Server s) {
        String n = names.get(s.folder.getAbsolutePath());
        return n == null || n.isEmpty() ? s.level : n;
    }

    public static List<SaveFile> entries(Minecraft mc, Map<SaveFile, OpenHostList.Server> into) {
        List<SaveFile> out = new ArrayList<>();
        ISaveFormat own;
        try { own = mc.getSaveFormat(); } catch (Throwable t) { return out; }
        for (OpenHostList.Server s : OpenHostList.all()) {
            try {

                ISaveFormat f = own.getClass().getConstructor(File.class).newInstance(s.folder);
                for (SaveFile sf : f.getSaveFileList()) {
                    if (!sf.getFileName().equals(s.level)) continue;
                    names.put(s.folder.getAbsolutePath(), sf.getDisplayName());

                    rename(sf, "fileName", s.folder.getName());
                    rename(sf, "displayName", label(mc, sf.getDisplayName()));
                    SaveFile mine = sf;
                    into.put(mine, s);
                    out.add(mine);
                }
            } catch (Throwable t) {
                OpenHouseMod.LOG.info("couldn't read the OpenHost world in {}: {}", s.folder, t.toString());
            }
        }
        return out;
    }

    static void rename(SaveFile sf, String field, String v) throws ReflectiveOperationException {
        java.lang.reflect.Field f = SaveFile.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(sf, v);
    }

    public static void open(File dir) {

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
        try { new ProcessBuilder(cmd, dir.getAbsolutePath()).start(); return; } catch (Exception ignored) { }
        try { java.awt.Desktop.getDesktop().open(dir); } catch (Throwable e) { OpenHouseMod.LOG.info("couldn't open {}: {}", dir, e.toString()); }
    }
}
