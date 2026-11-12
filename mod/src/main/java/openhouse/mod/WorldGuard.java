package openhouse.mod;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class WorldGuard {
    private WorldGuard() { }

    static final int KEEP = 8;
    static final String FLAG = "openhouse-check", CHECKED = "openhouse-checked";

    static final String CHECK_VERSION = "1";
    private static final Object LOCK = new Object();

    public static volatile String pendingChat;

    static File backupsRoot(File world) {
        return new File(new File(OpenHouseMod.dataDir(), "backups"), world.getName());
    }

    public static File backup(File world, String why) {
        synchronized (LOCK) {
            try {
                return backupLocked(world, why);
            } catch (Throwable t) {
                OpenHouseMod.LOG.warn("couldn't back up the world {} ({}): {}", world.getName(), why, t.toString());
                return null;
            }
        }
    }

    private static File backupLocked(File world, String why) throws IOException {
        if (!new File(world, "level.dat").isFile()) return null;
        File root = backupsRoot(world);
        List<File> old = backups(world);
        File prev = old.isEmpty() ? null : old.get(0);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File dest = new File(root, stamp + "-" + why);
        for (int i = 2; dest.exists(); i++) dest = new File(root, stamp + "-" + why + "-" + i);
        long t0 = System.currentTimeMillis();
        int linked = 0, copied = 0;
        Path from = world.toPath();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(from)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path f : files) {
            String rel = from.relativize(f).toString();
            String name = f.getFileName().toString();

            if (name.equals("session.lock") || name.equals(FLAG) || name.equals(CHECKED) || name.endsWith(".repair")) continue;
            Path to = dest.toPath().resolve(rel);
            Files.createDirectories(to.getParent());
            Path same = prev == null ? null : prev.toPath().resolve(rel);
            if (same != null && Files.isRegularFile(same) && Files.size(same) == Files.size(f)
                    && Files.getLastModifiedTime(same).equals(Files.getLastModifiedTime(f))) {
                try {
                    Files.createLink(to, same);
                    linked++;
                    continue;
                } catch (Exception e) {

                }
            }
            Files.copy(f, to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        Files.write(new File(dest, "complete").toPath(), (why + "\n").getBytes(StandardCharsets.UTF_8));
        OpenHouseMod.LOG.info("backed up {} ({}): {} files copied, {} unchanged, {} ms -> {}", world.getName(), why, copied, linked,
                System.currentTimeMillis() - t0, dest);
        prune(world);
        return dest;
    }

    static List<File> backups(File world) {
        File[] all = backupsRoot(world).listFiles(File::isDirectory);
        List<File> out = new ArrayList<>();
        if (all == null) return out;
        Arrays.sort(all, Comparator.comparing(File::getName).reversed());
        for (File b : all) {
            if (new File(b, "complete").isFile()) out.add(b);
            else if (System.currentTimeMillis() - b.lastModified() > 10 * 60_000L) deleteTree(b);
        }
        return out;
    }

    static void prune(File world) {
        List<File> all = backups(world);
        for (int i = KEEP; i < all.size(); i++) deleteTree(all.get(i));
    }

    static void deleteTree(File f) {
        try (Stream<Path> s = Files.walk(f.toPath())) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { }
    }

    public static void handingOver(File world) {
        try {
            Files.write(new File(world, FLAG).toPath(), new byte[0]);
        } catch (IOException e) {
            OpenHouseMod.LOG.warn("couldn't mark {} for a check after Open to Friends: {}", world.getName(), e.toString());
        }
        backup(world, "before-friends");
    }

    public static void beforeOpen(Minecraft mc, String worldDirName) {
        try {
            File world = new File(new File(FabricLoader.getInstance().getGameDir().toFile(), "saves"), worldDirName);
            if (!new File(world, "level.dat").isFile()) return;
            synchronized (LOCK) {
                File checked = new File(world, CHECKED);
                boolean flagged = new File(world, FLAG).isFile();
                boolean never = !checked.isFile() || !CHECK_VERSION.equals(read(checked));
                if (flagged || never) check(mc, world);
                List<File> b = backups(world);
                if (b.isEmpty() || System.currentTimeMillis() - new File(b.get(0), "complete").lastModified() > 24 * 3600_000L) backupLocked(world, "daily");
            }
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't check the world {} before opening it: {}", worldDirName, t.toString());
        }
    }

    private static void check(Minecraft mc, File world) throws IOException {
        long t0 = System.currentTimeMillis();
        progress(mc, "Checking your world", null, 0);
        List<File> backups = backups(world);
        if (backups.size() > 4) backups = backups.subList(0, 4);
        WorldRepair.Report report = new WorldRepair.Report();
        boolean[] backedUp = { false };
        Runnable beforeWrite = () -> {
            if (backedUp[0]) return;
            backedUp[0] = true;
            try {
                if (backupLocked(world, "before-repair") == null) throw new IOException("no backup made");
            } catch (IOException e) {

                throw new RuntimeException("can't back up the world before repairing it: " + e.getMessage(), e);
            }
        };
        List<File> dirs = regionDirs(world);
        for (File dir : dirs) {
            String rel = world.toPath().relativize(dir.toPath()).toString();
            List<File> same = new ArrayList<>();
            for (File b : backups) same.add(new File(b, rel));
            WorldRepair.repair(dir, same, (stage, pct) -> progress(mc, "Checking your world", stage, pct), report, beforeWrite);
        }
        Files.deleteIfExists(new File(world, FLAG).toPath());
        Files.write(new File(world, CHECKED).toPath(), CHECK_VERSION.getBytes(StandardCharsets.UTF_8));
        OpenHouseMod.LOG.info("checked {}: {} chunks, {} damaged, {} repaired, {} restored, {} left as they were ({} ms)", world.getName(),
                report.chunks, report.damaged, report.repaired, report.restored, report.unfixable, System.currentTimeMillis() - t0);
        if (report.damaged == 0) return;
        for (String l : report.lines) OpenHouseMod.LOG.info("  {}", l);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File txt = new File(backupsRoot(world), "repair-" + stamp + ".txt");
        try {
            Files.createDirectories(txt.getParentFile().toPath());
            Files.write(txt.toPath(), String.join("\n", report.lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        if (report.changed()) {
            pendingChat = "OpenHouse repaired " + (report.repaired + report.restored) + " damaged chunk"
                    + (report.repaired + report.restored == 1 ? "" : "s") + " in this world. The world as it was is backed up in openhouse/backups.";
        }
    }

    static List<File> regionDirs(File world) throws IOException {
        List<File> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(world.toPath(), 4)) {
            s.filter(p -> Files.isDirectory(p) && p.getFileName().toString().equals("region")).forEach(p -> out.add(p.toFile()));
        }
        return out;
    }

    private static void progress(Minecraft mc, String title, String stage, int pct) {
        try {
            if (mc == null || mc.loadingScreen == null) return;
            if (stage == null) { mc.loadingScreen.progressStart(title); mc.loadingScreen.progressStage(""); }
            else { mc.loadingScreen.progressStage(stage); mc.loadingScreen.progressStagePercentage(pct); }
        } catch (Throwable ignored) { }
    }

    private static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim(); } catch (IOException e) { return ""; }
    }
}
