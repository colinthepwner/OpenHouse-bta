package openhouse.mod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class OpenHousePreLaunch implements PreLaunchEntrypoint {

    private static volatile java.nio.file.Path configRoot;
    private static boolean unpacked;

    public static synchronized java.nio.file.Path configRoot() {
        if (!unpacked) {
            unpacked = true;
            try { roomConfigs(); } catch (Exception e) { OpenHouseMod.LOG.warn("couldn't unpack the host's mod settings: {}", e.toString()); }
        }
        return configRoot;
    }

    static void roomConfigs() throws Exception {
        String pack = System.getProperty("openhouse.pack", "");
        String modsFolder = System.getProperty("fabric.modsFolder", "");
        if (pack.isEmpty() || modsFolder.isEmpty()) return;
        File packDir = new File(modsFolder);
        File zip = new File(packDir, "share-config.zip");
        if (!zip.isFile()) return;
        File cfg = new File(packDir, "config");
        deleteTree(cfg);
        unzip(zip, cfg);
        Object loader = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl").getField("INSTANCE").get(null);
        Field f = loader.getClass().getDeclaredField("configDir");
        f.setAccessible(true);
        f.set(loader, cfg.toPath());
        configRoot = packDir.toPath();
        OpenHouseMod.LOG.info("using the host's mod settings ({})", cfg);
    }

    @Override
    public void onPreLaunch() {
        try {
            run();
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't set up the room's configs / id table: {}", t.toString());
        }
    }

    static void run() throws Exception {
        File game = FabricLoader.getInstance().getGameDir().toFile();
        File data = new File(game, OpenHouseMod.MOD_ID);
        File table = new File(game, "uselessNumericalInstance.dat");
        File mine = new File(data, "uselessNumericalInstance.dat.mine");
        File noneMarker = new File(data, "uselessNumericalInstance.dat.none");
        String pack = System.getProperty("openhouse.pack", "");
        String modsFolder = System.getProperty("fabric.modsFolder", "");
        if (!pack.isEmpty() && !modsFolder.isEmpty()) {
            File packDir = new File(modsFolder);
            configRoot();
            File hostTable = new File(packDir, "share-uselessnumerical.dat");
            if (hostTable.isFile()) {
                data.mkdirs();
                if (!mine.exists() && !noneMarker.exists()) {
                    if (table.isFile()) Files.copy(table.toPath(), mine.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    else noneMarker.createNewFile();
                }
                Files.copy(hostTable.toPath(), table.toPath(), StandardCopyOption.REPLACE_EXISTING);
                OpenHouseMod.LOG.info("using the host's id table for UselessNumerical");
            }
        } else if (mine.isFile() || noneMarker.exists()) {
            if (mine.isFile()) Files.move(mine.toPath(), table.toPath(), StandardCopyOption.REPLACE_EXISTING);
            else table.delete();
            noneMarker.delete();
            OpenHouseMod.LOG.info("put this game's own UselessNumerical id table back");
        }
    }

    static void unzip(File zip, File to) throws IOException {
        java.nio.file.Path root = to.toPath().toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                java.nio.file.Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) continue;
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
