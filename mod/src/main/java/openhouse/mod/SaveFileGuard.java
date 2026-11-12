package openhouse.mod;

import com.mojang.nbt.NbtIo;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class SaveFileGuard {
    private SaveFileGuard() { }

    static final long MAX = 16L << 20;

    public static List<String> check(File savesDir, File backupsRoot, Consumer<String> log) {
        List<String> out = new ArrayList<>();
        File[] worlds = savesDir.listFiles(File::isDirectory);
        if (worlds == null) return out;
        for (File w : worlds) {
            if (!new File(w, "level.dat").isFile() && !new File(w, "level.dat_old").isFile()) continue;
            for (File f : candidates(w)) {
                try {
                    String r = checkFile(w, f, backupsRoot == null ? null : new File(backupsRoot, w.getName()));
                    if (r != null) { out.add(w.getName() + ": " + r); log.accept(w.getName() + ": " + r); }
                } catch (Throwable t) {

                    log.accept("couldn't check " + w.getName() + "/" + rel(w, f) + ": " + t);
                }
            }
        }
        return out;
    }

    static List<File> candidates(File world) {
        List<File> out = new ArrayList<>();
        addDats(out, world, 0);
        addDats(out, new File(world, "data"), 8);
        addDats(out, new File(world, "players"), 0);
        File[] dims = new File(world, "dimensions").listFiles(File::isDirectory);
        if (dims != null) for (File d : dims) addDats(out, d, 0);
        return out;
    }

    private static void addDats(List<File> out, File dir, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { if (depth > 0) addDats(out, f, depth - 1); continue; }
            if (f.getName().endsWith(".dat") && f.length() <= MAX) out.add(f);
        }
    }

    static String checkFile(File world, File f, File backups) throws IOException {
        if (!gzip(f) || readable(f)) return null;
        String rel = rel(world, f);
        List<File> tries = new ArrayList<>();
        tries.add(new File(f.getParentFile(), f.getName() + "_new"));
        tries.add(new File(f.getParentFile(), f.getName() + "_old"));
        File[] bs = backups == null ? null : backups.listFiles(File::isDirectory);
        if (bs != null) {

            Arrays.sort(bs, Comparator.comparing(File::getName).reversed());
            for (File b : bs) if (new File(b, "complete").isFile()) tries.add(new File(b, rel));
        }
        File good = null;
        for (File t : tries) if (t.isFile() && readable(t)) { good = t; break; }
        File aside = new File(f.getParentFile(), f.getName() + ".broken");
        for (int i = 2; aside.exists(); i++) aside = new File(f.getParentFile(), f.getName() + ".broken-" + i);
        Files.move(f.toPath(), aside.toPath());
        if (good == null) {
            if (rel.equals("level.dat"))
                return "level.dat is damaged and there's no good copy to put back - moved it to " + aside.getName()
                        + " so the game can start; the world is hidden from the list until a good level.dat is put in its folder";
            return rel + " is damaged and there's no good copy to put back - moved it to " + aside.getName()
                    + " so the game can start (whatever keeps it starts that part fresh)";
        }

        Files.copy(good.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        String from = good.getParentFile().equals(f.getParentFile()) ? good.getName() : "OpenHouse backup " + backupName(backups, good);
        return rel + " was damaged - put back the one from " + from + " (the damaged one is kept as " + aside.getName() + ")";
    }

    private static String backupName(File backups, File good) {
        Path p = backups.toPath().relativize(good.toPath());
        return p.getNameCount() > 0 ? p.getName(0).toString() : good.getParent();
    }

    static String rel(File world, File f) {
        return world.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    }

    static boolean gzip(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean readable(File f) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            return NbtIo.readCompressed(in) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
