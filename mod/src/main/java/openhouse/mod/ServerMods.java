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

    static Set<String> culprits(List<String> lines, List<Entry> in, Set<String> leftOutIds) {
        return ModCrash.culprits(lines, jarsOf(in), leftOutIds);
    }

    static Map<String, List<File>> jarsOf(List<Entry> in) {
        Map<String, List<File>> out = new LinkedHashMap<>();
        for (Entry x : in) out.computeIfAbsent(x.id, k -> new ArrayList<>()).add(x.jar);
        return out;
    }

    static String crashMod(List<String> lines, List<Entry> in) {
        return ModCrash.crashMod(lines, jarsOf(in));
    }

    static Map<String, String> classIndex(List<Entry> in) {
        return ModCrash.classIndex(jarsOf(in));
    }
}
