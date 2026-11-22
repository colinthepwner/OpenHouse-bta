package openhouse.mod;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public final class IdFix {
    private IdFix() { }

    static Map<String, Integer> host;
    static Map<Integer, String> host_by_id;

    static Map<String, Integer> moved;
    static final java.util.Set<String> told = new java.util.HashSet<>();

    static synchronized void host_ids() {
        if (host != null) return;
        host = new HashMap<>();
        host_by_id = new HashMap<>();
        String pack = System.getProperty("openhouse.pack", ""), dir = System.getProperty("fabric.modsFolder", "");
        if (pack.isEmpty() || dir.isEmpty()) return;
        File f = new File(dir, "share-ids.txt");
        if (!f.isFile()) return;
        try {
            for (String l : Files.readAllLines(f.toPath())) {
                String[] t = l.trim().split(" ");
                if (t.length != 2 || t[0].startsWith("minecraft:")) continue;
                int n = Integer.parseInt(t[1]);
                host.put(t[0], n);
                host_by_id.put(n, t[0]);
            }
            OpenHouseMod.LOG.info("using the host's ids for {} mod blocks and items", host.size());
        } catch (Exception e) { OpenHouseMod.LOG.warn("couldn't read the host's ids: {}", e.toString()); }
    }

    static File moved_file() {
        File g = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();

        return openhouse.mod.server.ServerRoom.LAN ? new File(g.getAbsoluteFile().getParentFile(), "ids-moved.txt") : new File(new File(g, OpenHouseMod.MOD_ID), "ids-moved.txt");
    }

    static synchronized Map<String, Integer> moved() {
        if (moved != null) return moved;
        moved = new java.util.LinkedHashMap<>();
        if (!host.isEmpty()) return moved;
        try {
            File f = moved_file();
            if (f.isFile()) for (String l : Files.readAllLines(f.toPath())) {
                String[] t = l.trim().split(" ");
                if (t.length == 2 && !t[0].startsWith("#")) moved.put(t[0], Integer.parseInt(t[1]));
            }
        } catch (Exception e) { OpenHouseMod.LOG.warn("couldn't read {}: {}", moved_file(), e.toString()); }
        return moved;
    }

    static synchronized void remember(String key, int id) {
        if (!host.isEmpty() || Integer.valueOf(id).equals(moved().get(key))) return;
        moved.put(key, id);
        StringBuilder b = new StringBuilder("# mod blocks/items OpenHouse gave another id (two mods wanted one). keep it: worlds saved the numbers\n");
        for (Map.Entry<String, Integer> e : moved.entrySet()) b.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        try { File f = moved_file(); f.getParentFile().mkdirs(); Files.write(f.toPath(), b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception e) { OpenHouseMod.LOG.warn("couldn't save {}: {}", moved_file(), e.toString()); }
    }

    static boolean mine(String key) { return key != null && !key.startsWith("minecraft:"); }

    public static int item(int id, String key) {
        if (!mine(key)) return id;
        Object[] l = net.minecraft.core.item.Item.itemsList;

        for (Object o : l) if (o != null && key.equals(String.valueOf(((net.minecraft.core.item.Item) o).namespaceID))) return id;
        return pick(id, key, l, null, 16385, "item");
    }

    public static int block(int id, String key) {
        if (!mine(key)) return id;
        net.minecraft.core.block.Block<?>[] bl = net.minecraft.core.block.Blocks.blocksList;

        for (net.minecraft.core.block.Block<?> b : bl) if (b != null && key.equals(String.valueOf(b.namespaceId()))) return id;

        return pick(id, key, net.minecraft.core.block.Blocks.blocksList, net.minecraft.core.item.Item.itemsList, 1, "block");
    }

    static int pick(int id, String key, Object[] l, Object[] also, int low, String what) {
        host_ids();
        Integer h = host.get(key);
        if (h != null && free(h, l, also)) {
            if (h != id) OpenHouseMod.LOG.debug("{} {} takes the host's id {} (asked for {})", what, key, h, id);
            return h;
        }

        Integer m = moved().get(key);
        if (m != null && free(m, l, also) && !host_by_id.containsKey(m)) return m;
        String owner = host_by_id.get(id);
        boolean reserved = owner != null && !owner.equals(key) || moved().containsValue(id) && !Integer.valueOf(id).equals(m);
        if (id >= 0 && id < l.length && l[id] == null && !reserved) return id;
        if (id < 0 || id >= l.length) return id;
        for (int i = l.length - 1; i >= low; i--) {
            if (!free(i, l, also) || host_by_id.containsKey(i) || moved().containsValue(i)) continue;

            String why = l[id] != null ? "taken by " + name(l[id]) : owner != null ? "the host's " + owner + " has it" : "kept for another";
            String ns = key.indexOf(':') > 0 ? key.substring(0, key.indexOf(':')) : key;
            if (told.add(ns)) OpenHouseMod.LOG.info("{} {} wanted id {} ({}): it gets {}. more of {}'s may move too, all kept in {}", what, key, id, why, i, ns, moved_file().getName());
            else OpenHouseMod.LOG.debug("{} {} wanted id {} ({}): it gets {}", what, key, id, why, i);
            remember(key, i);
            return i;
        }
        return id;
    }

    static String name(Object o) {
        if (o instanceof net.minecraft.core.block.Block) return String.valueOf(((net.minecraft.core.block.Block<?>) o).namespaceId());
        if (o instanceof net.minecraft.core.item.Item) return String.valueOf(((net.minecraft.core.item.Item) o).namespaceID);
        return String.valueOf(o);
    }

    static boolean free(int i, Object[] l, Object[] also) {
        return i >= 0 && i < l.length && l[i] == null && (also == null || i >= also.length || also[i] == null);
    }
}
