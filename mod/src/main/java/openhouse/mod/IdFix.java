package openhouse.mod;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public final class IdFix {
    private IdFix() { }

    static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("OpenHouse");

    static Map<String, Integer> host;
    static Map<Integer, String> host_by_id;

    static Map<String, Integer> known;
    static final Map<Integer, String> known_by_id = new HashMap<>();
    static final java.util.Set<String> told = new java.util.HashSet<>();
    static boolean saving;

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
            LOG.info("using the host's ids for {} mod blocks and items", host.size());
        } catch (Exception e) { LOG.warn("couldn't read the host's ids: {}", e.toString()); }
    }

    static File data_dir() {
        File g = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();

        return System.getProperty("openhouse.lan") != null ? g.getAbsoluteFile().getParentFile() : new File(g, "openhouse");
    }
    static File known_file() { return new File(data_dir(), "mod-ids.txt"); }

    static synchronized Map<String, Integer> known() {
        if (known != null) return known;
        known = new java.util.LinkedHashMap<>();
        if (!host.isEmpty()) return known;
        for (File f : new File[] { new File(data_dir(), "ids-moved.txt"), known_file() }) {
            try {
                if (f.isFile()) for (String l : Files.readAllLines(f.toPath())) {
                    String[] t = l.trim().split(" ");
                    if (t.length == 2 && !t[0].startsWith("#")) known.put(t[0], Integer.parseInt(t[1]));
                }
            } catch (Exception e) { LOG.warn("couldn't read {}: {}", f, e.toString()); }
        }
        for (Map.Entry<String, Integer> e : known.entrySet()) known_by_id.putIfAbsent(e.getValue(), e.getKey());
        return known;
    }

    static synchronized void remember(String key, int id) {
        if (!host.isEmpty() || Integer.valueOf(id).equals(known().get(key))) return;
        known.put(key, id);
        known_by_id.putIfAbsent(id, key);
        if (saving) return;
        saving = true;
        Thread t = new Thread(() -> {
            try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
            save();
        }, "OpenHouse mod ids");
        t.setDaemon(true);
        t.start();
        try { Runtime.getRuntime().addShutdownHook(new Thread(IdFix::save)); } catch (Exception ignored) { }
    }

    static synchronized void save() {
        saving = false;
        StringBuilder b = new StringBuilder("# every mod block/item id this game has had. keep it: worlds saved the numbers, and a mod that turns up\n# later wanting one of these gets another\n");
        for (Map.Entry<String, Integer> e : known.entrySet()) b.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        try { File f = known_file(); f.getParentFile().mkdirs(); File tmp = new File(f.getPath() + ".part"); Files.write(tmp.toPath(), b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception e) { LOG.warn("couldn't save {}: {}", known_file(), e.toString()); }
    }

    static boolean oopsed;
    public static int oops(int id, Throwable t) {
        if (!oopsed) { oopsed = true; LOG.warn("mod id check broke, ids stay as the mods asked", t); }
        return id;
    }

    static Boolean other;
    static boolean other() {
        if (other == null) other = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("uselessnumerical");
        return other;
    }

    static boolean mine(String key) { return key != null && !key.startsWith("minecraft:") && !other(); }

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
            if (h != id) LOG.debug("{} {} takes the host's id {} (asked for {})", what, key, h, id);
            return h;
        }

        Integer m = known().get(key);
        if (m != null && free(m, l, also) && !host_by_id.containsKey(m)) { remember(key, m); return m; }
        String owner = host_by_id.get(id), had = known_by_id.get(id);
        boolean reserved = owner != null && !owner.equals(key) || had != null && !had.equals(key);
        if (id >= 0 && id < l.length && l[id] == null && !reserved) { remember(key, id); return id; }
        if (id < 0 || id >= l.length) return id;
        for (int i = l.length - 1; i >= low; i--) {
            if (!free(i, l, also) || host_by_id.containsKey(i) || known_by_id.containsKey(i)) continue;

            String why = l[id] != null ? "taken by " + name(l[id]) : owner != null ? "the host's " + owner + " has it" : "it's " + had + "'s in this game's worlds";
            String ns = key.indexOf(':') > 0 ? key.substring(0, key.indexOf(':')) : key;
            if (told.add(ns)) LOG.info("{} {} wanted id {} ({}): it gets {}. more of {}'s may move too, all kept in {}", what, key, id, why, i, ns, known_file().getName());
            else LOG.debug("{} {} wanted id {} ({}): it gets {}", what, key, id, why, i);
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
