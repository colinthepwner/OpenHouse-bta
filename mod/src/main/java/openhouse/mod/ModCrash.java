package openhouse.mod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModCrash {
    private ModCrash() { }

    static final Pattern MIXIN_FAILED = Pattern.compile("Mixin (?:prepare|apply|transformation) for mod ([A-Za-z0-9_\\-]+) failed");
    static final Pattern MIXIN_FAILED2 = Pattern.compile("from mod ([A-Za-z0-9_\\-]+)\\][^\\n]*FAILED");
    static final Pattern PROVIDED_BY = Pattern.compile("provided by '([A-Za-z0-9_\\-]+)'");
    static final Pattern REQUIRES = Pattern.compile("Mod '[^']*' \\(([A-Za-z0-9_\\-]+)\\).*requires.*\\(([A-Za-z0-9_\\-]+)\\)");
    static final Pattern FRAME = Pattern.compile("^\\s*at ([A-Za-z0-9_$.]+)\\.[A-Za-z0-9_$<>]+\\(");

    static Set<String> culprits(List<String> lines, Map<String, List<File>> jars, Set<String> leftOutIds) {
        Map<String, List<File>> byId = jars;
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
                if (classToMod == null) classToMod = classIndex(jars);
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
        if (classToMod == null) classToMod = classIndex(jars);
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

    static Map<String, String> classIndex(Map<String, List<File>> jars) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<File>> me : jars.entrySet()) for (File jar : me.getValue()) {
            try (ZipFile z = new ZipFile(jar)) {
                java.util.Enumeration<? extends ZipEntry> it = z.entries();
                while (it.hasMoreElements()) {
                    String n = it.nextElement().getName();
                    if (!n.endsWith(".class")) continue;
                    String c = n.substring(0, n.length() - 6);
                    out.putIfAbsent(c, me.getKey());
                    int d = c.indexOf('$');
                    if (d > 0) out.putIfAbsent(c.substring(0, d), me.getKey());
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    static final Pattern FRAME_METHOD = Pattern.compile("^\\s*at (?:[^/\\s]+//)?([A-Za-z0-9_$.]+)\\.([A-Za-z0-9_$<>]+)\\(");
    static final Pattern HANDLER = Pattern.compile("^[a-zA-Z]+\\$[a-z]{3}\\d{3}\\$([A-Za-z0-9_\\-]+)\\$");

    static String crashMod(List<String> lines, Map<String, List<File>> jars) {
        if (lines.isEmpty() || jars.isEmpty()) return null;
        Set<String> ids = jars.keySet();
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
            if (classToMod == null) classToMod = classIndex(jars);
            String id = classToMod.get(cls.replace('.', '/'));
            if (id != null) return id;
        }
        return null;
    }

    static boolean clientOnlyCrash(List<String> lines) {
        for (String l : lines) if (l.contains("Cannot load class") && l.contains("in environment type SERVER")) return true;
        return false;
    }

    static Map<String, List<File>> idsOf(List<File> jars) {
        Map<String, List<File>> out = new LinkedHashMap<>();
        for (File f : jars) {
            try (ZipFile z = new ZipFile(f)) {
                ZipEntry e = z.getEntry("fabric.mod.json");
                if (e == null) continue;
                String text;
                try (InputStream in = z.getInputStream(e)) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                Map<String, Object> m = openhouse.Json.obj(openhouse.Json.parse(text));
                String id = m == null ? null : openhouse.Json.str(m.get("id"));
                if (id != null) out.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(f);
            } catch (IOException | RuntimeException ignored) {

            }
        }
        return out;
    }
}
