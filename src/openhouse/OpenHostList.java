package openhouse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipFile;

public final class OpenHostList {
    private OpenHostList() { }

    public static final class Server {
        public final File folder;
        public final int port;
        public final String level;
        Server(File folder, int port, String level) { this.folder = folder; this.port = port; this.level = level; }
        public File world() { return new File(folder, level); }
    }

    static final String NAME = "openhost-servers.properties";
    static volatile File gameDir;
    public static void game(File g) { gameDir = g; }

    static final String TEST_DIR = System.getenv("OPENHOUSE_TEST_LISTDIR");

    static List<File> lists() {
        List<File> l = new ArrayList<>();
        if (TEST_DIR != null) { l.add(new File(TEST_DIR, NAME)); return l; }
        String home = System.getProperty("user.home");
        if (home != null) l.add(new File(new File(home, ".openhouse"), NAME));
        File g = gameDir;
        if (g != null) l.add(new File(new File(g, "openhouse"), NAME));
        return l;
    }

    static Properties load(File f) {
        Properties p = new Properties();
        if (f.isFile()) try (InputStream in = new FileInputStream(f)) { p.load(in); } catch (IOException | RuntimeException ignored) { }
        return p;
    }

    static String key(File f) {
        try { return f.getCanonicalPath(); } catch (IOException e) { return f.getAbsolutePath(); }
    }

    public static synchronized void add(File folder) {
        if (folder == null) return;
        String k = key(folder);
        for (File f : lists()) {
            try {
                Properties p = load(f);
                p.setProperty(k, String.valueOf(System.currentTimeMillis()));
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { p.store(o, "OpenHost servers on this computer (OpenHouse shows their worlds in the world list)"); }
            } catch (IOException | RuntimeException ignored) { }
        }
        synchronized (found) { found.put(k, folder); }
    }

    public static Server of(File folder) {
        try {
            if (folder == null || !folder.isDirectory()) return null;
            File props = new File(folder, "server.properties");
            if (!props.isFile() || !isOpenHost(folder)) return null;
            Properties p = load(props);
            String level = p.getProperty("level-name", "world").trim();
            if (level.isEmpty()) level = "world";
            int port;
            try { port = Integer.parseInt(p.getProperty("server-port", "25565").trim()); } catch (NumberFormatException e) { port = 25565; }
            Server s = new Server(folder, port, level);
            return new File(s.world(), "level.dat").isFile() ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isOpenHost(File folder) {
        File d = new File(folder, "openhouse");
        if (new File(d, "lib").isDirectory() || new File(d, "server-classes.jsa").isFile() || new File(folder, "openhouse-moved.properties").isFile()) return true;
        return jar(folder) != null;
    }

    public static File jar(File folder) {
        File plain = new File(folder, "OpenHost.jar");
        if (plain.isFile()) return plain;
        File[] js = folder.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (js == null) return null;
        for (File j : js) {
            try (ZipFile z = new ZipFile(j)) {
                if (z.getEntry("openhouse/boot/OpenHostBoot.class") != null) return j;
            } catch (IOException | RuntimeException ignored) { }
        }
        return null;
    }

    static final Map<String, File> found = new LinkedHashMap<>();
    static volatile long scanned;

    public static List<Server> all() {
        Map<String, File> cand = new LinkedHashMap<>();
        for (File f : lists()) for (String k : load(f).stringPropertyNames()) cand.put(k, new File(k));
        File g = gameDir;
        if (g != null) kids(new File(g, "openhost"), cand);
        synchronized (found) { cand.putAll(found); }
        scanSoon();
        List<Server> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (File f : cand.values()) {
            Server s = of(f);
            if (s != null && seen.add(key(s.world()))) out.add(s);
        }
        return out;
    }

    static void kids(File dir, Map<String, File> into) {
        File[] fs;
        try { fs = dir.listFiles(File::isDirectory); } catch (RuntimeException e) { return; }
        if (fs != null) for (File f : fs) into.put(key(f), f);
    }

    static void scanSoon() {
        if (TEST_DIR != null) return;
        if (System.currentTimeMillis() - scanned < 120_000) return;
        scanned = System.currentTimeMillis();
        Thread t = new Thread(OpenHostList::scan, "OpenHouse finds OpenHost servers");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static void scan() {
        long until = System.currentTimeMillis() + 4000;
        List<File> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            File h = new File(home);
            for (String n : new String[] { "Desktop", "Documents", "Downloads", "OneDrive/Desktop", "OneDrive/Documents", "Servers", "servers", "BTA", "Minecraft" })
                roots.add(new File(h, n));
            roots.add(h);
        }
        File g = gameDir;
        if (g != null) { roots.add(new File(g, "openhost")); if (g.getParentFile() != null) roots.add(g.getParentFile()); }
        for (File r : roots) look(r, r.getName().equals(new File(home == null ? "" : home).getName()) ? 1 : 2, until);
    }

    static final java.util.Set<String> SKIP = new java.util.HashSet<>(java.util.Arrays.asList(
            "appdata", "library", "node_modules", ".git", "windows", "program files", "$recycle.bin", "saves", "mods", "world", "logs", "config", "openhouse"));

    static void look(File dir, int depth, long until) {
        if (depth < 0 || System.currentTimeMillis() > until) return;
        try {
            if (!dir.isDirectory() || SKIP.contains(dir.getName().toLowerCase(java.util.Locale.ROOT))) return;
            if (new File(dir, "server.properties").isFile()) {
                if (of(dir) != null) synchronized (found) { found.put(key(dir), dir); }
                return;
            }
            File[] fs = dir.listFiles(File::isDirectory);
            if (fs != null) for (File f : fs) look(f, depth - 1, until);
        } catch (RuntimeException ignored) { }
    }
}
