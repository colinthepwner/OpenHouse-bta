package openhouse.mod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModOrigin;

public final class ServerMods {
    private ServerMods() { }

    static final Set<String> BUILTIN = new HashSet<>(java.util.Arrays.asList("minecraft", "java", "fabricloader", "mixinextras"));

    static final class Entry {
        final String id, version, name;
        final File jar;
        final boolean clientOnly;
        Entry(String id, String version, String name, File jar, boolean clientOnly) {
            this.id = id; this.version = version; this.name = name; this.jar = jar; this.clientOnly = clientOnly;
        }
    }

    static List<Entry> loaded() {
        File folder = LocalServer.modsFolder().getAbsoluteFile();
        List<Entry> out = new ArrayList<>();
        Set<File> seen = new HashSet<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            String id = c.getMetadata().getId();
            if (BUILTIN.contains(id) || "builtin".equals(c.getMetadata().getType())) continue;
            ModOrigin o;
            try { o = c.getOrigin(); } catch (Throwable t) { continue; }
            if (o.getKind() != ModOrigin.Kind.PATH) continue;
            for (java.nio.file.Path p : o.getPaths()) {
                File f = p.toFile().getAbsoluteFile();
                if (!f.isFile() || !f.getName().toLowerCase().endsWith(".jar") || !folder.equals(f.getParentFile()) || !seen.add(f)) continue;
                out.add(new Entry(id, c.getMetadata().getVersion().getFriendlyString(), c.getMetadata().getName(), f,
                        c.getMetadata().getEnvironment() == ModEnvironment.CLIENT));
            }
        }
        return out;
    }

    static File memoryFile() {
        return new File(new File(OpenHouseMod.dataDir(), "server"), "client-side-mods.properties");
    }

    static Map<String, String> remembered() {
        Map<String, String> out = new LinkedHashMap<>();
        Properties p = new Properties();
        File f = memoryFile();
        if (f.isFile()) {
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { p.load(in); } catch (IOException ignored) { }
        }
        for (String k : p.stringPropertyNames()) out.put(k, p.getProperty(k));
        return out;
    }

    static void remember(Map<String, String> idToVersion) {
        Map<String, String> all = remembered();
        all.putAll(idToVersion);
        File f = memoryFile();
        f.getParentFile().mkdirs();
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("# Mods that stopped the Open to Friends server from starting - OpenHouse leaves them out of\n"
                    + "# it (they still run in the game). mod id = version; a newer version gets tried again.\n"
                    + "# Delete a line (or this file) to try a mod on the server again.\n");
            for (Map.Entry<String, String> e : all.entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");
        } catch (IOException e) {
            OpenHouseMod.LOG.warn("can't save {}: {}", f, e.toString());
        }
    }

    static List<Entry> forServer(List<Entry> all, Map<String, String> skip, List<Entry> leftOut) {
        List<Entry> in = new ArrayList<>();
        for (Entry e : all) {
            if (e.clientOnly) continue;
            if (e.version.equals(skip.get(e.id))) { leftOut.add(e); continue; }
            in.add(e);
        }
        return in;
    }

    static File sync(List<Entry> in) throws IOException {
        File dir = new File(new File(OpenHouseMod.dataDir(), "server"), "mods");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        Set<String> names = new HashSet<>();
        for (Entry e : in) {
            File to = new File(dir, e.jar.getName());
            names.add(to.getName());
            if (to.isFile() && to.length() == e.jar.length() && to.lastModified() == e.jar.lastModified()) continue;
            Files.copy(e.jar.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.isFile() && !names.contains(f.getName())) f.delete();
        return dir;
    }

    static final Pattern MIXIN_FAILED = Pattern.compile("Mixin (?:prepare|apply|transformation) for mod ([A-Za-z0-9_\\-]+) failed");
    static final Pattern MIXIN_FAILED2 = Pattern.compile("from mod ([A-Za-z0-9_\\-]+)\\][^\\n]*FAILED");
    static final Pattern PROVIDED_BY = Pattern.compile("provided by '([A-Za-z0-9_\\-]+)'");
    static final Pattern REQUIRES = Pattern.compile("Mod '[^']*' \\(([A-Za-z0-9_\\-]+)\\).*requires.*\\(([A-Za-z0-9_\\-]+)\\)");
    static final Pattern FRAME = Pattern.compile("^\\s*at ([A-Za-z0-9_$.]+)\\.[A-Za-z0-9_$<>]+\\(");

    static Set<String> culprits(List<String> lines, List<Entry> in, Set<String> leftOutIds) {
        Map<String, Entry> byId = new HashMap<>();
        for (Entry e : in) byId.put(e.id, e);
        Set<String> out = new LinkedHashSet<>();
        for (String l : lines) {
            if (l.contains("/WARN]")) continue;
            Matcher m = MIXIN_FAILED.matcher(l);
            while (m.find()) if (byId.containsKey(m.group(1))) out.add(m.group(1));
            m = MIXIN_FAILED2.matcher(l);
            while (m.find()) if (byId.containsKey(m.group(1))) out.add(m.group(1));
        }
        if (!out.isEmpty()) return out;
        Map<String, String> classToMod = null;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains("/WARN]")) continue;
            Matcher m = PROVIDED_BY.matcher(l);
            boolean named = false;
            while (m.find()) if (byId.containsKey(m.group(1))) { out.add(m.group(1)); named = true; }

            if (!named && l.trim().startsWith("Suppressed:") && !l.contains("provided by")) {
                if (classToMod == null) classToMod = classIndex(in);
                String id = firstModFrame(lines, i + 1, classToMod, true);
                if (id != null) out.add(id);
            }
        }
        for (String l : lines) {
            Matcher m = REQUIRES.matcher(l);
            if (m.find() && byId.containsKey(m.group(1)) && (leftOutIds.contains(m.group(2)) || !byId.containsKey(m.group(2)))) out.add(m.group(1));
        }
        if (!out.isEmpty()) return out;
        int root = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).trim().startsWith("Caused by:")) root = i;
        if (classToMod == null) classToMod = classIndex(in);
        String id = firstModFrame(lines, root >= 0 ? root : 0, classToMod, false);
        if (id != null) out.add(id);
        return out;
    }

    static String firstModFrame(List<String> lines, int from, Map<String, String> classToMod, boolean block) {
        for (int i = from; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (block && (t.startsWith("Suppressed:") || t.startsWith("Caused by:"))) {
                if (t.startsWith("Caused by:")) continue;
                return null;
            }
            Matcher m = FRAME.matcher(lines.get(i));
            if (!m.find()) continue;
            String cls = m.group(1);
            if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("net.minecraft.")
                    || cls.startsWith("net.fabricmc.") || cls.startsWith("org.spongepowered.") || cls.startsWith("com.llamalad7.")) continue;
            String id = classToMod.get(cls.replace('.', '/'));
            if (id != null) return id;
        }
        return null;
    }

    static final Pattern FRAME_METHOD = Pattern.compile("^\\s*at (?:[^/\\s]+//)?([A-Za-z0-9_$.]+)\\.([A-Za-z0-9_$<>]+)\\(");
    static final Pattern HANDLER = Pattern.compile("^[a-zA-Z]+\\$[a-z]{3}\\d{3}\\$([A-Za-z0-9_\\-]+)\\$");

    static String crashMod(List<String> lines, List<Entry> in) {
        if (lines.isEmpty() || in.isEmpty()) return null;
        Set<String> ids = new HashSet<>();
        for (Entry e : in) ids.add(e.id);
        int root = 0;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).trim().startsWith("Caused by:")) root = i;
        Map<String, String> classToMod = null;
        for (int i = root; i < lines.size(); i++) {
            Matcher m = FRAME_METHOD.matcher(lines.get(i));
            if (!m.find()) continue;
            String cls = m.group(1);
            Matcher h = HANDLER.matcher(m.group(2));
            if (h.find() && ids.contains(h.group(1))) return h.group(1);
            if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("net.minecraft.")
                    || cls.startsWith("net.fabricmc.") || cls.startsWith("org.spongepowered.") || cls.startsWith("com.llamalad7.")) continue;
            if (classToMod == null) classToMod = classIndex(in);
            String id = classToMod.get(cls.replace('.', '/'));
            if (id != null) return id;
        }
        return null;
    }

    static Map<String, String> classIndex(List<Entry> in) {
        Map<String, String> out = new HashMap<>();
        for (Entry e : in) {
            try (ZipFile z = new ZipFile(e.jar)) {
                java.util.Enumeration<? extends ZipEntry> it = z.entries();
                while (it.hasMoreElements()) {
                    String n = it.nextElement().getName();
                    if (!n.endsWith(".class")) continue;
                    String c = n.substring(0, n.length() - 6);
                    out.putIfAbsent(c, e.id);
                    int d = c.indexOf('$');
                    if (d > 0) out.putIfAbsent(c.substring(0, d), e.id);
                }
            } catch (IOException ignored) { }
        }
        return out;
    }
}
