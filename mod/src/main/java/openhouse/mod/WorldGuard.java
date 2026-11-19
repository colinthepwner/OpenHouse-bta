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

    static final int MAX_KEEP = 40;

    static final int KEEP_NEWEST = 8;

    private static boolean no_prune;
    static final long HOUR = 3600_000L, DAY = 24 * HOUR;
    public static final String THUMB = "thumb.png";
    static final long MIN_FREE = 1L << 30;

    static boolean roomFor(File dir, long bytes) {
        long free = dir.getUsableSpace();
        return free <= 0 || free - bytes >= MIN_FREE;
    }

    public static void finishRewinds(File saves) {
        File[] all = saves.listFiles(File::isDirectory);
        if (all == null) return;
        synchronized (LOCK) {
            for (File d : all) {
                String n = d.getName();
                try {
                    if (n.endsWith(".openhouse-rewind")) { deleteTree(d); OpenHouseMod.LOG.info("cleared a half-made rewind copy {}", n); continue; }

                    if (isVisit(n) && !inWorld()) { deleteTree(d); OpenHouseMod.LOG.info("cleared a timeline visit left behind {}", n); continue; }
                    if (!n.endsWith(".openhouse-old")) continue;
                    File world = new File(saves, n.substring(0, n.length() - ".openhouse-old".length()));
                    if (!world.exists()) {
                        if (d.renameTo(world)) OpenHouseMod.LOG.info("put {} back after a rewind that didn't finish", world.getName());
                    } else if (new File(world, "level.dat").isFile()) {

                        boolean kept = false;
                        for (File b : backups(world)) if (why(b).equals("before-rewind")) kept = true;
                        if (kept) deleteTree(d);
                        else {
                            File keep = new File(saves, world.getName() + " (before rewind)");
                            for (int i = 2; keep.exists(); i++) keep = new File(saves, world.getName() + " (before rewind " + i + ")");
                            d.renameTo(keep);
                        }
                    }
                } catch (Throwable t) {
                    OpenHouseMod.LOG.warn("couldn't tidy {}: {}", n, t.toString());
                }
            }
        }
    }
    static final String FLAG = "openhouse-check", CHECKED = "openhouse-checked";

    static final String CHECK_VERSION = "1";
    private static final Object LOCK = new Object();

    public static volatile String pendingChat;

    static File backupsRoot(File world) {
        return new File(new File(OpenHouseMod.dataDir(), "backups"), world.getName());
    }

    public static File backup(File world, String why) {

        if (isVisit(world.getName())) return null;
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

        long need = 0;
        for (Path f : files) {
            Path same = prev == null ? null : prev.toPath().resolve(from.relativize(f).toString());
            try {
                if (same == null || !Files.isRegularFile(same) || Files.size(same) != Files.size(f)
                        || !Files.getLastModifiedTime(same).equals(Files.getLastModifiedTime(f))) need += Files.size(f);
            } catch (IOException e) { need += 0; }
        }
        Files.createDirectories(root.toPath());
        if (!roomFor(root, need)) {
            OpenHouseMod.LOG.warn("not backing up {} ({}): it needs {} MB and the disk would be left with under {} MB free", world.getName(), why, need >> 20, MIN_FREE >> 20);
            return null;
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
        File thumb = new File(dest, THUMB);
        if (!WorldShots.write(world.getName(), thumb) && prev != null && new File(prev, THUMB).isFile()) {

            try { Files.createLink(thumb.toPath(), new File(prev, THUMB).toPath()); }
            catch (Exception e) { try { Files.copy(new File(prev, THUMB).toPath(), thumb.toPath()); } catch (Exception ignored) { } }
        }
        Files.write(new File(dest, "complete").toPath(), (why + "\n").getBytes(StandardCharsets.UTF_8));
        OpenHouseMod.LOG.info("backed up {} ({}): {} files copied, {} unchanged, {} ms -> {}", world.getName(), why, copied, linked,
                System.currentTimeMillis() - t0, dest);
        if (!no_prune) prune(world);
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
        long now = System.currentTimeMillis();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<File> keep = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            File b = all.get(i);
            long age = Math.max(0, now - made(b));
            String bucket;
            if (i < KEEP_NEWEST || age < 2 * HOUR) bucket = null;
            else if (age < DAY) bucket = "h" + age / HOUR;
            else if (age < 14 * DAY) bucket = "d" + age / DAY;
            else if (age < 90 * DAY) bucket = "w" + age / (7 * DAY);
            else if (age < 365 * DAY) bucket = "m" + age / (30 * DAY);
            else bucket = "gone";
            if (bucket == null || (!bucket.equals("gone") && seen.add(bucket))) keep.add(b);
            else deleteTree(b);
        }
        while (keep.size() > MAX_KEEP) deleteTree(keep.remove(keep.size() - 1));

        long budget = Math.max(512L << 20, 3 * treeSize(world));
        long[] own = new long[keep.size()];
        long total = 0;
        for (int i = 0; i < keep.size(); i++) { own[i] = ownBytes(keep.get(i), i + 1 < keep.size() ? keep.get(i + 1) : null); total += own[i]; }
        for (int i = keep.size() - 1; i >= KEEP_NEWEST && total > budget; i--) {
            deleteTree(keep.get(i));
            total -= own[i];
            OpenHouseMod.LOG.info("backups of {} over {} MB, dropped {}", world.getName(), budget >> 20, keep.get(i).getName());
        }
    }

    public static long made(File b) {
        try { return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").parse(b.getName().substring(0, 19)).getTime(); }
        catch (Exception e) { return new File(b, "complete").lastModified(); }
    }

    public static String why(File b) {
        String n = b.getName();
        if (n.length() <= 20) return "";
        n = n.substring(20);
        int d = n.lastIndexOf('-');
        if (d > 0 && n.substring(d + 1).chars().allMatch(Character::isDigit)) n = n.substring(0, d);
        return n;
    }

    static long treeSize(File f) {
        try (Stream<Path> s = Files.walk(f.toPath())) {
            return s.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException e) { return 0; }
    }

    static long ownBytes(File b, File older) {
        Path from = b.toPath();
        try (Stream<Path> s = Files.walk(from)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    long n = Files.size(p);
                    if (older != null) {
                        Path o = older.toPath().resolve(from.relativize(p).toString());
                        if (Files.isRegularFile(o) && Files.size(o) == n && Files.getLastModifiedTime(o).equals(Files.getLastModifiedTime(p))) return 0;
                    }
                    return n;
                } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) { return 0; }
    }

    public static File saveDir(String worldDirName) {
        return new File(new File(FabricLoader.getInstance().getGameDir().toFile(), "saves"), worldDirName);
    }

    public static List<File> timeline(File world) {
        synchronized (LOCK) { return backups(world); }
    }

    public static void left(String worldDirName) {
        if (worldDirName == null || isVisit(worldDirName)) return;
        File world = saveDir(worldDirName);
        Thread t = new Thread(() -> {
            RegionFiles.wait_saves();
            backup(world, "played");
        }, "OpenHouse backup " + worldDirName);
        t.start();
    }

    public interface Progress { void at(String what, int pct); }

    static boolean inWorld() {
        try { Minecraft mc = Minecraft.getMinecraft(); return mc != null && mc.currentWorld != null; } catch (Throwable t) { return false; }
    }

    public static final String VISIT = ".openhouse-visit";

    public static volatile String visit_of, visit_name;

    public static boolean isVisit(String dir) { return dir != null && dir.endsWith(VISIT); }
    public static String visitDir(String world) { return world + VISIT; }

    public static boolean visiting() { return visit_of != null && visitDir(visit_of).equals(OpenHouseMod.singlePlayerWorld); }

    public static void startVisit(File world, File backup, Progress p) throws IOException {
        synchronized (LOCK) {
            if (!new File(backup, "complete").isFile()) throw new IOException("that backup isn't complete");
            if (!roomFor(world.getParentFile(), treeSize(backup))) throw new IOException("not enough free disk space");
            File v = new File(world.getParentFile(), visitDir(world.getName()));
            RegionFiles.release();
            if (v.exists()) deleteTree(v);
            copyOut(backup, v, p);
        }
    }

    public static void endVisit(File world, boolean replace) throws IOException {
        synchronized (LOCK) {
            File v = new File(world.getParentFile(), visitDir(world.getName()));
            RegionFiles.wait_saves();
            RegionFiles.release();
            try {
                if (!replace) { deleteTree(v); return; }
                if (!new File(v, "level.dat").isFile()) throw new IOException("the loaded save is gone");
                no_prune = true;
                try {
                    if (new File(world, "level.dat").isFile() && backupLocked(world, "before-rewind") == null) throw new IOException("couldn't back up the world first");
                } finally {
                    no_prune = false;
                }
                File old = new File(world.getParentFile(), world.getName() + ".openhouse-old");

                if (old.exists()) finishRewinds(world.getParentFile());
                if (old.exists()) throw new IOException(old.getName() + " is still there - move it out of saves first");
                Files.write(new File(v, CHECKED).toPath(), CHECK_VERSION.getBytes(StandardCharsets.UTF_8));
                Files.deleteIfExists(new File(v, "session.lock").toPath());
                if (world.exists() && !world.renameTo(old)) throw new IOException("the world folder is in use");
                if (!v.renameTo(world)) {
                    old.renameTo(world);
                    throw new IOException("couldn't move the loaded save in place");
                }
                deleteTree(old);
                OpenHouseMod.LOG.info("replaced {} with the save loaded from the timeline", world.getName());
                prune(world);
            } finally {
                visit_of = visit_name = null;
            }
        }
    }

    private static void copyOut(File backup, File to_dir, Progress p) throws IOException {
        List<Path> files = new ArrayList<>();
        Path from = backup.toPath();
        try (Stream<Path> s = Files.walk(from)) { s.filter(Files::isRegularFile).forEach(files::add); }
        int i = 0;
        for (Path f : files) {
            String rel = from.relativize(f).toString();
            i++;
            if (rel.equals("complete") || rel.equals(THUMB)) continue;
            Path to = to_dir.toPath().resolve(rel);
            Files.createDirectories(to.getParent());
            Files.copy(f, to, StandardCopyOption.COPY_ATTRIBUTES);
            if (Files.size(to) != Files.size(f)) { deleteTree(to_dir); throw new IOException("a copied file came out short: " + rel); }
            p.at("copying", i * 100 / files.size());
        }
        if (!new File(to_dir, "level.dat").isFile()) { deleteTree(to_dir); throw new IOException("that backup has no level.dat"); }

        Files.write(new File(to_dir, CHECKED).toPath(), CHECK_VERSION.getBytes(StandardCharsets.UTF_8));
    }

    static void deleteTree(File f) {
        try (Stream<Path> s = Files.walk(f.toPath())) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { }
    }

    public static void handingOver(File world) {
        if (isVisit(world.getName())) return;
        try {
            Files.write(new File(world, FLAG).toPath(), new byte[0]);
        } catch (IOException e) {
            OpenHouseMod.LOG.warn("couldn't mark {} for a check after Open to Friends: {}", world.getName(), e.toString());
        }
        backup(world, "before-friends");
    }

    public static void beforeOpen(Minecraft mc, String worldDirName) {
        try {
            File world = saveDir(worldDirName);
            if (!new File(world, "level.dat").isFile()) return;
            if (isVisit(worldDirName)) return;
            if (visit_of != null) visit_of = visit_name = null;
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
