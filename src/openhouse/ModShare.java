package openhouse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModShare {
    public static final class Mod {
        public final String id, version, name, environment, file, sha256;
        public final long size;
        public transient File path;

        Mod(String id, String version, String name, String environment, String file, String sha256, long size) {
            this.id = id; this.version = version; this.name = name; this.environment = environment;
            this.file = file; this.sha256 = sha256; this.size = size;
        }
    }

    private ModShare() { }

    public static List<Mod> scan(File modsFolder) {
        List<Mod> out = new ArrayList<>();
        File[] files = modsFolder.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            try {
                String id = null, version = null, name = null, env = "*";
                try (ZipFile z = new ZipFile(f)) {
                    ZipEntry e = z.getEntry("fabric.mod.json");
                    if (e != null) {
                        try (InputStream in = z.getInputStream(e)) {
                            Map<String, Object> m = Json.obj(Json.parse(new String(readAll(in), StandardCharsets.UTF_8)));
                            if (m != null) {
                                id = Json.str(m.get("id"));
                                version = Json.str(m.get("version"));
                                name = Json.str(m.get("name"));
                                if (Json.str(m.get("environment")) != null) env = Json.str(m.get("environment"));
                            }
                        } catch (RuntimeException badJson) {

                        }
                    }
                }
                if ("server".equals(env)) continue;
                String sha = Rtc.sha256(f);
                Mod m = new Mod(id != null ? id : f.getName(), version != null ? version : sha.substring(0, 12),
                        name != null ? name : (id != null ? id : f.getName()), env, f.getName(), sha, f.length());
                m.path = f;
                out.add(m);
            } catch (IOException e) {

            }
        }
        return out;
    }

    public static String toJson(List<Mod> mods) {
        List<Object> list = new ArrayList<>();
        for (Mod m : mods)
            list.add(Json.map("id", m.id, "version", m.version, "name", m.name, "environment", m.environment,
                    "file", m.file, "sha256", m.sha256, "size", (double) m.size));
        return Json.write(Json.map("mods", list));
    }

    public static List<Mod> fromJson(String json) {
        List<Mod> out = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(json));
        Object list = root == null ? null : root.get("mods");
        if (!(list instanceof List)) return out;
        for (Object o : (List<?>) list) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            Object size = m.get("size");
            String file = Json.str(m.get("file"));
            String sha = Json.str(m.get("sha256"));

            if (file == null || sha == null || !file.matches("[A-Za-z0-9._+\\-]{1,120}") || !sha.matches("[0-9a-f]{64}")) continue;
            out.add(new Mod(Json.str(m.get("id")), Json.str(m.get("version")), Json.str(m.get("name")),
                    Json.str(m.get("environment")), file, sha, size instanceof Number ? ((Number) size).longValue() : -1));
        }
        return out;
    }

    public static File writeHostManifest(List<Mod> mods, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        Map<String, Object> paths = new java.util.LinkedHashMap<>();
        for (Mod m : mods) if (m.path != null) paths.put(m.sha256, m.path.getAbsolutePath());
        Map<String, Object> root = Json.obj(Json.parse(toJson(mods)));
        root.put("paths", paths);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(dest), StandardCharsets.UTF_8)) {
            w.write(Json.write(root));
        }
        return dest;
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
