package openhouse.vt;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class Live {
    private Live() { }

    private static Map<String, ClassNode> g7, shim;
    private static String g7_key;

    public static final class Result {
        public int adapted, rewired, stubbed, unresolved;
        public String id;
    }

    public static final String PACK_STUBS = "openhouse-v7-stubs.jar";
    static final String PACK_PREFIX = "openhouse/v7/stub/pack/";
    private static boolean in_pack;

    public static synchronized void begin(List<File> jars) throws IOException {
        Vt.STUBS.clear(); Vt.LIB_REFS.clear(); Vt.PACK.clear();
        for (File f : jars) Vt.scan(Files.readAllBytes(f.toPath()));
        in_pack = true;
    }

    public static final String PACK_LIBS = "openhouse-v7-libs.jar";

    public static final class LibPlan { public final Map<String, Boolean> pkgs = new java.util.TreeMap<>(); public boolean client; }

    public static synchronized LibPlan lib_plan() throws IOException {
        LibPlan p = new LibPlan();
        ClassLoader cl = Live.class.getClassLoader();
        Map<String, Boolean> missing = new HashMap<>();
        try (InputStream in = Live.class.getResourceAsStream("/openhouse/v7/g8libs.txt")) {
            if (in == null) return p;
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] t = l.trim().split(" ");
                if (t.length < 3) continue;

                if (!g7.containsKey(t[1]) && cl.getResource(t[1] + ".class") == null) missing.put(t[0], t[2].equals("C"));
            }
        }
        Set<String> groups = new HashSet<>();
        for (String r : Vt.LIB_REFS) if (missing.containsKey(r)) groups.add(lib_group(r));
        for (Map.Entry<String, Boolean> e : missing.entrySet()) if (groups.contains(lib_group(e.getKey()))) {
            p.pkgs.put(e.getKey(), e.getValue());
            p.client |= e.getValue();
        }
        return p;
    }

    public static final String PACK_LIBS_BTA = "openhouse-v7-libs-bta.jar";

    public static synchronized void libs(LibPlan p, Src server8, Src client8, File out, File out_bta, List<File> game, Consumer<String> log) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>(), bta = new LinkedHashMap<>();
        files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"openhouse_v7_libs\",\"version\":\"1\",\"name\":\"BTA 8.0.1's libraries\",\"environment\":\"*\"}").getBytes(StandardCharsets.UTF_8));
        bta.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"openhouse_v7_libs_bta\",\"version\":\"1\",\"name\":\"BTA 8.0.1's own libraries\",\"environment\":\"*\"}").getBytes(StandardCharsets.UTF_8));
        boolean kt = false;
        for (String k : p.pkgs.keySet()) kt |= k.startsWith("kotlin");
        for (Src src : new Src[] { server8, client8 }) {
            if (src == null) continue;
            boolean is_client = src == client8;
            List<String> want = new java.util.ArrayList<>();
            for (String n : src.names()) {
                if (files.containsKey(n) || n.endsWith("/")) continue;
                String pkg = n.indexOf('/') > 0 ? n.substring(0, n.lastIndexOf('/')) : "";
                Boolean c = p.pkgs.get(pkg);
                boolean take = c != null && c == is_client && !n.startsWith("META-INF/");
                if (kt && !is_client && n.startsWith("META-INF/") && n.endsWith(".kotlin_module")) take = true;
                if (take) want.add(n);
            }
            Map<String, byte[]> got = src.read(want);
            for (String n : want) {
                byte[] b = got.get(n);
                if (b == null) continue;
                if (n.endsWith(".class") && calls_game(b)) { bta.put(n, b); continue; }

                files.put(n, n.endsWith(".class") && new String(b, StandardCharsets.ISO_8859_1).contains("org/joml/") ? joml7(b) : b);
            }
        }
        write(out, files);
        if (bta.size() > 1) {
            File raw = File.createTempFile("ohvt", ".jar");
            try {
                write(raw, bta);
                mod(raw, out_bta, game, log);
            } finally {
                raw.delete();
            }
        } else out_bta.delete();
    }

    public static final String PACK_ART = "openhouse-v7-art.jar";

    public interface Src {
        java.util.Collection<String> names() throws IOException;
        Map<String, byte[]> read(java.util.Collection<String> want) throws IOException;
    }

    public static Src zip_src(File f) {
        return new Src() {
            public java.util.Collection<String> names() throws IOException {
                List<String> l = new java.util.ArrayList<>();
                try (ZipFile z = new ZipFile(f)) { for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) { ZipEntry en = e.nextElement(); if (!en.isDirectory()) l.add(en.getName()); } }
                return l;
            }
            public Map<String, byte[]> read(java.util.Collection<String> want) throws IOException {
                Map<String, byte[]> m = new HashMap<>();
                try (ZipFile z = new ZipFile(f)) {
                    for (String n : want) { ZipEntry en = z.getEntry(n); if (en != null) try (InputStream s = z.getInputStream(en)) { m.put(n, s.readAllBytes()); } }
                }
                return m;
            }
        };
    }

    public static boolean art_file(String n) {
        boolean lang = n.startsWith("assets/minecraft/lang/") && n.endsWith(".lang");
        boolean model = (n.startsWith("assets/minecraft/models/") || n.startsWith("assets/minecraft/blockstates/")) && n.endsWith(".json");
        boolean pic = (n.startsWith("assets/minecraft/textures/block/") || n.startsWith("assets/minecraft/textures/item/")) && (n.endsWith(".png") || n.endsWith(".mcmeta"));
        return lang || model || pic;
    }

    public static synchronized void art(Src client8, File out, List<File> game) throws IOException {
        Set<String> have = new HashSet<>();
        for (File g : game) {
            try (ZipFile z = new ZipFile(g)) { for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) have.add(e.nextElement().getName()); }
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"openhouse_v7_art\",\"version\":\"1\",\"name\":\"BTA 8.0.1's block pictures and names\",\"environment\":\"*\"}").getBytes(StandardCharsets.UTF_8));

        List<String> want = new java.util.ArrayList<>();
        for (String n : client8.names()) if (art_file(n) && !have.contains(n)) want.add(n);
        Map<String, byte[]> got = client8.read(want);
        for (String n : want) if (got.containsKey(n)) files.put(n, got.get(n));
        write(out, files);
    }

    static boolean calls_game(byte[] b) {
        String s = new String(b, StandardCharsets.ISO_8859_1);
        return s.contains("net/minecraft/") || s.contains("com/mojang/");
    }

    static String lib_group(String pkg) {
        String[] p = pkg.split("/");
        boolean org_like = p.length > 1 && java.util.Arrays.asList("com", "org", "net", "io", "it", "de", "me", "dev").contains(p[0]);
        return org_like ? p[0] + "/" + p[1] : p[0];
    }

    public static synchronized void end(File out) throws IOException {
        in_pack = false;
        File stubs = File.createTempFile("ohvt", ".jar");
        try {
            Vt.write_stubs(stubs.getPath());
            Set<String> stubbed = new HashSet<>(Vt.STUBS.keySet());
            Remapper r = remapper(stubbed, PACK_PREFIX);
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"openhouse_v7_stubs\",\"version\":\"1\",\"name\":\"OpenHouse 8.0 stand-ins\",\"environment\":\"*\","
                    + "\"depends\":{\"openhouse\":\"*\"}}").getBytes(StandardCharsets.UTF_8));
            try (ZipFile z = new ZipFile(stubs)) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (!en.getName().endsWith(".class")) continue;
                    try (InputStream s = z.getInputStream(en)) {
                        String n = en.getName().substring(0, en.getName().length() - 6);
                        files.put(r.map(n) + ".class", remap(s.readAllBytes(), r));
                    }
                }
            }
            write(out, files);
            Vt.STUBS.clear();
            Vt.PACK.clear();
        } finally {
            stubs.delete();
        }
    }

    static byte[] joml7(byte[] b) {
        ClassWriter w = new ClassWriter(0);
        new ClassReader(b).accept(new ClassRemapper(w, new Remapper() {
            @Override public String map(String n) { return n.startsWith("org/joml/") ? "openhouse/v7/joml/" + n.substring(9) : n; }
        }), 0);
        return w.toByteArray();
    }

    static Remapper remapper(Set<String> stubbed, String prefix) {
        return new Remapper() {
            @Override public String map(String n) {
                if (stubbed.contains(n)) return prefix + n;
                if (n.startsWith("net/minecraft/") && shim.containsKey(n)) return "openhouse/v7/shim/" + n;
                if (n.startsWith("org/joml/")) return "openhouse/v7/joml/" + n.substring(9);
                return n;
            }
        };
    }

    public static synchronized Result mod(File in, File out, List<File> game, Consumer<String> log) throws IOException {
        maps(game, log);
        String id = mod_id(in);
        String safe = id.replaceAll("[^A-Za-z0-9_]", "_");
        if (!in_pack) Vt.STUBS.clear();
        Vt v = new Vt(g7, shim, id);
        File tmp = File.createTempFile("ohvt", ".jar"), stubs = File.createTempFile("ohvt", ".jar");
        try {
            v.translate(in.getPath(), tmp.getPath());
            if (!in_pack) Vt.write_stubs(stubs.getPath());

            Set<String> stubbed = new HashSet<>(Vt.STUBS.keySet());
            Remapper r = remapper(stubbed, in_pack ? PACK_PREFIX : "openhouse/v7/stub/" + safe + "/");
            Map<String, byte[]> files = remap_jar(Files.readAllBytes(tmp.toPath()), r);
            if (!in_pack) try (ZipFile z = new ZipFile(stubs)) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (!en.getName().endsWith(".class")) continue;
                    try (InputStream s = z.getInputStream(en)) {
                        String n = en.getName().substring(0, en.getName().length() - 6);
                        files.put(r.map(n) + ".class", remap(s.readAllBytes(), r));
                    }
                }
            }
            write(out, files);
            Result res = new Result();
            res.id = id; res.adapted = v.adapted; res.rewired = v.rewired; res.stubbed = v.stubbed;
            for (int n : v.unresolved.values()) res.unresolved += n;
            int opened = v.widen.size();
            for (Vt sub : v.nested) opened += sub.widen.size();
            log.accept("translated " + in.getName() + " (" + id + ") for this BTA: " + v.adapted + " adapted, " + v.rewired + " to stand-ins, "
                    + v.stubbed + " missing, " + stubbed.size() + " missing classes, " + opened + " opened");
            v.unresolved.forEach((k, n) -> log.accept("  not on this BTA: " + k + " x" + n));
            return res;
        } finally {
            tmp.delete();
            stubs.delete();
        }
    }

    private static void maps(List<File> game, Consumer<String> log) throws IOException {
        String key = game.toString();
        if (g7 != null && key.equals(g7_key)) return;
        Map<String, ClassNode> g = new HashMap<>();
        for (File f : game) if (f != null && f.isFile()) g.putAll(Vt.load(f.getPath()));
        Vt.GAME_JARS.clear();
        for (File f : game) if (f != null && f.isFile()) Vt.GAME_JARS.add(f.getPath());
        g7 = g;
        g7_key = key;

        try {
            Object fl = Class.forName("net.fabricmc.loader.api.FabricLoader").getMethod("getInstance").invoke(null);
            Object mr = fl.getClass().getMethod("getMappingResolver").invoke(fl);
            Vt.AW_NS = (String) mr.getClass().getMethod("getCurrentRuntimeNamespace").invoke(mr);
        } catch (Throwable ignored) { }
        shim = own_shims();
        log.accept("translator ready: " + g.size() + " game classes, " + shim.size() + " stand-ins");
    }

    private static Map<String, ClassNode> own_shims() throws IOException {
        Map<String, ClassNode> m = new HashMap<>();
        File me;
        try { me = new File(Live.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception e) { throw new IOException("can't find OpenHouse's own jar: " + e); }
        Remapper back = new Remapper() {
            @Override public String map(String n) {
                if (n.startsWith("openhouse/v7/shim/")) return n.substring(18);
                if (n.startsWith("openhouse/v7/joml/")) return "org/joml/" + n.substring(18);
                return n;
            }
        };
        try (ZipFile z = new ZipFile(me)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                String n = en.getName();
                if (!n.startsWith("openhouse/v7/") || !n.endsWith(".class") || n.startsWith("openhouse/v7/stub/") || n.startsWith("openhouse/v7/gen/")) continue;
                try (InputStream s = z.getInputStream(en)) {
                    ClassNode c = new ClassNode();
                    new ClassReader(s.readAllBytes()).accept(new ClassRemapper(c, back), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    m.put(c.name, c);
                }
            }
        }
        return m;
    }

    static String mod_id(File jar) {
        try (ZipFile z = new ZipFile(jar)) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) try (InputStream s = z.getInputStream(e)) {
                String j = new String(s.readAllBytes(), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(j);
                if (m.find()) return m.group(1);
            }
        } catch (IOException ignored) { }
        return jar.getName().replaceAll("[-+].*", "").replaceAll("\\.jar$", "");
    }

    static Map<String, byte[]> remap_jar(byte[] jar, Remapper r) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                byte[] b = z.readAllBytes();
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/")) m.put(r.map(n.substring(0, n.length() - 6)) + ".class", remap(b, r));
                else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) m.put(n, zip(remap_jar(b, r)));
                else m.put(n, b);
            }
        }
        return m;
    }

    static byte[] remap(byte[] b, Remapper r) {
        ClassWriter w = new ClassWriter(0);
        new ClassReader(b).accept(new ClassRemapper(w, r), 0);
        return w.toByteArray();
    }

    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) { put(z, files); }
        return b.toByteArray();
    }

    static void write(File f, Map<String, byte[]> files) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) { put(z, files); }
    }

    private static void put(ZipOutputStream z, Map<String, byte[]> files) throws IOException {
        Set<String> dirs = new TreeSet<>();
        for (String k : files.keySet()) for (int i = k.indexOf('/'); i > 0; i = k.indexOf('/', i + 1)) dirs.add(k.substring(0, i + 1));
        for (String d : dirs) { z.putNextEntry(new ZipEntry(d)); z.closeEntry(); }
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            z.putNextEntry(new ZipEntry(e.getKey()));
            z.write(e.getValue());
            z.closeEntry();
        }
    }
}
