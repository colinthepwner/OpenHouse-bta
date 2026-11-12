package openhouse.mod;

import com.mojang.nbt.NbtIo;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class LevelDatGuard {
    private LevelDatGuard() { }

    public static List<String> check(File savesDir, File backupsRoot, Consumer<String> log) {
        List<String> out = new ArrayList<>();
        File[] worlds = savesDir.listFiles(File::isDirectory);
        if (worlds == null) return out;
        for (File w : worlds) {
            try {
                String r = checkWorld(w, backupsRoot == null ? null : new File(backupsRoot, w.getName()));
                if (r != null) { out.add(w.getName() + ": " + r); log.accept(w.getName() + ": " + r); }
            } catch (Throwable t) {

                log.accept("couldn't check " + w.getName() + "'s level.dat: " + t);
            }
        }
        return out;
    }

    static String checkWorld(File world, File backups) throws IOException {
        File dat = new File(world, "level.dat");
        if (!dat.isFile() || readable(dat)) return null;
        List<File> tries = new ArrayList<>();
        tries.add(new File(world, "level.dat_new"));
        tries.add(new File(world, "level.dat_old"));
        File[] bs = backups == null ? null : backups.listFiles(File::isDirectory);
        if (bs != null) {

            Arrays.sort(bs, Comparator.comparing(File::getName).reversed());
            for (File b : bs) if (new File(b, "complete").isFile()) tries.add(new File(b, "level.dat"));
        }
        File good = null;
        for (File f : tries) if (f.isFile() && readable(f)) { good = f; break; }
        File aside = new File(world, "level.dat.broken");
        for (int i = 2; aside.exists(); i++) aside = new File(world, "level.dat.broken-" + i);
        Files.move(dat.toPath(), aside.toPath());
        if (good == null)
            return "level.dat is damaged and there's no good copy to put back - moved it to " + aside.getName()
                    + " so the game can start; the world is hidden from the list until a good level.dat is put in its folder";

        Files.copy(good.toPath(), dat.toPath(), StandardCopyOption.REPLACE_EXISTING);
        String from = good.getParentFile().equals(world) ? good.getName() : "OpenHouse backup " + good.getParentFile().getName();
        return "level.dat was damaged - put back the one from " + from + " (the damaged one is kept as " + aside.getName() + ")";
    }

    static boolean readable(File f) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            return NbtIo.readCompressed(in) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
