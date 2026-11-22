package openhouse.mod;

import openhouse.bridge.Nbt;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

final class LevelFix {
    final File world;
    String name;
    Object player;

    private LevelFix(File world) { this.world = world; }

    @SuppressWarnings("unchecked")
    static LevelFix before(File world, String username) {
        LevelFix f = new LevelFix(world);
        try {
            Map<String, Object> data = data(world);
            if (data != null) { f.name = (String) data.get("LevelName"); f.player = data.get("Player"); }
            if (f.player instanceof Map && username != null) {
                File pf = new File(new File(world, "players"), username + ".dat");
                pf.getParentFile().mkdirs();
                Files.write(pf.toPath(), Nbt.zip((Map<String, Object>) f.player));
            }
        } catch (Throwable t) {
            OpenHouseMod.LOG.info("couldn't read {}'s level.dat before opening it: {}", world.getName(), t.toString());
        }
        return f;
    }

    @SuppressWarnings("unchecked")
    void after(String username, java.util.UUID uuid) {
        try {
            File ld = new File(world, "level.dat");
            Map<String, Object> root = Nbt.unzip(Files.readAllBytes(ld.toPath()));
            if (root == null || !(root.get("Data") instanceof Map)) return;
            Map<String, Object> data = (Map<String, Object>) root.get("Data");
            boolean changed = false;
            if (name != null && !name.equals(data.get("LevelName"))) { data.put("LevelName", name); changed = true; }
            if (player != null && !data.containsKey("Player")) {

                Object p = player;
                for (String f : new String[] { username + ".dat", uuid + ".dat" }) {
                    File pf = new File(new File(world, "players"), f);
                    if (!pf.isFile()) continue;
                    Map<String, Object> m = Nbt.unzip(Files.readAllBytes(pf.toPath()));
                    if (m != null) { p = m; break; }
                }
                data.put("Player", p);
                changed = true;
            }
            if (!changed) return;
            File tmp = new File(world, "level.dat.oh");
            Files.write(tmp.toPath(), Nbt.zip(root));
            Files.move(tmp.toPath(), ld.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            OpenHouseMod.LOG.info("put {}'s name and player back after Open to Friends", world.getName());
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't put {}'s name / player back: {}", world.getName(), t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(File world) throws Exception {
        Map<String, Object> root = Nbt.unzip(Files.readAllBytes(new File(world, "level.dat").toPath()));
        return root != null && root.get("Data") instanceof Map ? (Map<String, Object>) root.get("Data") : null;
    }
}
