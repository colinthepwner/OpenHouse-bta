package openhouse.mod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class ModSync {
    private ModSync() { }

    static final long GRACE = 4 * 3600_000L;

    static final class Result {
        final List<String> added = new ArrayList<>(), removed = new ArrayList<>(), kept = new ArrayList<>();
        boolean changed() { return !added.isEmpty() || !removed.isEmpty(); }
    }

    static File book(File srv) { return new File(srv, "openhouse/mod-sync.properties"); }

    static void moved(File srv, List<String> jars) {
        Properties p = new Properties();
        long now = System.currentTimeMillis();
        for (String j : jars) p.setProperty(j, String.valueOf(now));
        save(srv, p);
    }

    static Result run(File srv, File gameMods) throws IOException {
        Result r = new Result();
        File mods = new File(srv, "mods");
        mods.mkdirs();
        Properties p = load(srv);
        long now = System.currentTimeMillis();

        Map<String, File> game = new LinkedHashMap<>();
        File self = openhouse.Rtc.self().getCanonicalFile();
        File[] gj = gameMods.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (gj != null) for (File j : gj) {
            if (j.getCanonicalFile().equals(self) || "openhouse".equals(MoveToOpenHost.modId(j))) continue;
            game.put(j.getName(), j);
        }
        Map<String, String> gameIds = new LinkedHashMap<>();
        for (Map.Entry<String, File> e : game.entrySet()) gameIds.put(e.getKey(), String.valueOf(MoveToOpenHost.modId(e.getValue())));

        for (Map.Entry<String, File> e : game.entrySet()) {
            File there = new File(mods, e.getKey());
            if (there.isFile() && there.length() == e.getValue().length()) continue;

            if (new File(mods, e.getKey() + ".disabled").exists() || "client".equals(environment(e.getValue()))) continue;
            String id = gameIds.get(e.getKey());
            for (String ours : new ArrayList<>(p.stringPropertyNames())) {
                File old = new File(mods, ours);
                if (!ours.equals(e.getKey()) && old.isFile() && id.equals(String.valueOf(MoveToOpenHost.modId(old)))) {
                    remove(srv, old);
                    p.remove(ours);
                }
            }
            put(srv, e.getValue(), there);
            if (p.getProperty(e.getKey()) == null) p.setProperty(e.getKey(), String.valueOf(now));
            r.added.add(e.getKey());
        }

        java.util.Set<String> ids = new java.util.HashSet<>(gameIds.values());
        for (String ours : new ArrayList<>(p.stringPropertyNames())) {
            File j = new File(mods, ours);
            if (!j.isFile()) { p.remove(ours); continue; }
            if (game.containsKey(ours) || ids.contains(String.valueOf(MoveToOpenHost.modId(j)))) continue;
            long in;
            try { in = Long.parseLong(p.getProperty(ours)); } catch (NumberFormatException e) { in = 0; }
            boolean clientOnly = "client".equals(environment(j));
            if (clientOnly || now - in < GRACE) {
                remove(srv, j);
                p.remove(ours);
                r.removed.add(ours);
            } else {
                r.kept.add(ours);
            }
        }
        save(srv, p);
        if (r.changed() || !r.kept.isEmpty()) openhouse.OhCrash.note("mod sync for " + srv.getName() + ": added " + r.added + ", removed " + r.removed + ", kept " + r.kept);
        return r;
    }

    static void remove(File srv, File jar) throws IOException {
        if (jar.delete() || !jar.exists()) return;
        queue(srv, "rm " + jar.getName());
    }

    static void put(File srv, File from, File to) throws IOException {
        try {
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException locked) {
            File staged = new File(srv, "openhouse/mod-staged/" + to.getName());
            staged.getParentFile().mkdirs();
            Files.copy(from.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
            queue(srv, "put " + to.getName());
        }
    }

    static void queue(File srv, String line) throws IOException {
        File f = new File(srv, "openhouse/mod-changes.txt");
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    static String environment(File jar) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar)) {
            java.util.zip.ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) {
                Map<String, Object> j = openhouse.Json.obj(openhouse.Json.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                return j == null ? null : openhouse.Json.str(j.get("environment"));
            }
        } catch (Exception e) { return null; }
    }

    static Properties load(File srv) {
        Properties p = new Properties();
        File f = book(srv);
        if (f.isFile()) try (InputStream in = new FileInputStream(f)) { p.load(in); } catch (IOException ignored) { }
        return p;
    }

    static void save(File srv, Properties p) {
        File f = book(srv);
        f.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(f)) { p.store(o, "mods Move to OpenHost / the game put here, and when (ModSync)"); } catch (IOException ignored) { }
    }
}
