package openhouse.mod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClientCode {
    private ClientCode() { }

    static final String CLIENT = "net/minecraft/client/";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ENV_ANNOTATION = "Lnet/fabricmc/api/Environment;";

    public static String serverCrasher(File jar) {
        try (ZipFile z = new ZipFile(jar)) {
            Map<String, Object> fmj = openhouse.Json.obj(openhouse.Json.parse(text(z, "fabric.mod.json")));
            if (fmj == null) return null;

            if ("client".equals(openhouse.Json.str(fmj.get("environment")))) return null;
            for (Object cfg : list(fmj.get("mixins"))) {
                String name = openhouse.Json.str(cfg);
                if (name == null) {
                    Map<String, Object> o = openhouse.Json.obj(cfg);
                    name = o == null ? null : openhouse.Json.str(o.get("config"));
                }
                String t = name == null ? null : text(z, name);
                Map<String, Object> cj = null;
                if (t != null) {
                    try { cj = openhouse.Json.obj(openhouse.Json.parse(t)); } catch (RuntimeException ignored) { }
                }
                if (cj == null) continue;
                String pkg = openhouse.Json.str(cj.get("package"));
                pkg = pkg == null ? "" : pkg.replace('.', '/') + "/";

                List<Object> common = new ArrayList<>(list(cj.get("mixins")));
                common.addAll(list(cj.get("server")));
                for (Object m : common) {
                    String mn = openhouse.Json.str(m);
                    if (mn == null) continue;
                    String cls = pkg + mn.replace('.', '/');
                    byte[] b = bytes(z, cls + ".class");
                    if (b == null) continue;
                    String why = clientUse(b);
                    if (why != null) return "its mixin " + simple(cls) + " " + why;
                }
            }
        } catch (IOException | RuntimeException ignored) {

        }
        return null;
    }

    private static String clientUse(byte[] b) {
        Pool p = pool(b);
        if (p == null) return null;
        Ann a = classAnnotations(p, b);
        if (a == null || a.clientOnly) return null;
        String target = null;
        for (String t : a.mixinTargets) if (!t.startsWith(CLIENT)) { target = t; break; }
        if (target == null) return null;
        String used = firstClientRef(p);
        if (used == null) return null;
        return "(attached to " + simple(target) + ") uses " + used.replace('/', '.');
    }

    private static String firstClientRef(Pool p) {
        for (int i = 1; i < p.tag.length; i++) {
            if (p.tag[i] != 9 && p.tag[i] != 10 && p.tag[i] != 11) continue;
            String owner = className(p, p.a[i]);
            if (owner != null && owner.startsWith(CLIENT)) return owner;
        }
        return null;
    }

    private static final class Pool {
        byte[] tag;
        int[] a;
        String[] utf8;
        int body;
    }

    private static Pool pool(byte[] b) {
        if (b.length < 10 || u4(b, 0) != 0xCAFEBABEL) return null;
        int n = u2(b, 8);
        Pool p = new Pool();
        p.tag = new byte[n];
        p.a = new int[n];
        p.utf8 = new String[n];
        int i = 10;
        for (int k = 1; k < n; k++) {
            int tag = b[i++] & 0xFF;
            p.tag[k] = (byte) tag;
            switch (tag) {
                case 1: {
                    int len = u2(b, i);
                    i += 2;
                    p.utf8[k] = new String(b, i, len, StandardCharsets.UTF_8);
                    i += len;
                    break;
                }
                case 7: case 8: case 16: case 19: case 20:
                    p.a[k] = u2(b, i);
                    i += 2;
                    break;
                case 15:
                    i += 3;
                    break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    p.a[k] = u2(b, i);
                    i += 4;
                    break;
                case 3: case 4:
                    i += 4;
                    break;
                case 5: case 6:
                    i += 8;
                    k++;
                    break;
                default:
                    return null;
            }
            if (i > b.length) return null;
        }
        p.body = i;
        return p;
    }

    private static String className(Pool p, int idx) {
        if (idx <= 0 || idx >= p.tag.length) return null;
        if (p.tag[idx] == 9 || p.tag[idx] == 10 || p.tag[idx] == 11) return className(p, p.a[idx]);
        if (p.tag[idx] != 7) return null;
        int n = p.a[idx];
        return n > 0 && n < p.utf8.length ? p.utf8[n] : null;
    }

    private static final class Ann {
        final List<String> mixinTargets = new ArrayList<>();
        boolean clientOnly;
    }

    private static Ann classAnnotations(Pool p, byte[] b) {
        int i = p.body + 6;
        if (i + 2 > b.length) return null;
        i += 2 + 2 * u2(b, i);
        for (int pass = 0; pass < 2; pass++) {
            if (i + 2 > b.length) return null;
            int cnt = u2(b, i);
            i += 2;
            for (int k = 0; k < cnt; k++) {
                i += 6;
                if (i + 2 > b.length) return null;
                int ac = u2(b, i);
                i += 2;
                for (int j = 0; j < ac; j++) {
                    if (i + 6 > b.length) return null;
                    i += 6 + (int) u4(b, i + 2);
                }
            }
        }
        if (i + 2 > b.length) return null;
        int ac = u2(b, i);
        i += 2;
        Ann out = new Ann();
        for (int j = 0; j < ac; j++) {
            if (i + 6 > b.length) return null;
            String name = p.utf8[u2(b, i)];
            int len = (int) u4(b, i + 2);
            int at = i + 6;
            i = at + len;
            if (!"RuntimeVisibleAnnotations".equals(name) && !"RuntimeInvisibleAnnotations".equals(name)) continue;
            if (at + 2 > b.length) continue;
            int num = u2(b, at);
            int q = at + 2;
            for (int k = 0; k < num && q > 0; k++) q = annotation(p, b, q, out);
        }
        return out;
    }

    private static int annotation(Pool p, byte[] b, int i, Ann out) {
        if (i + 4 > b.length) return -1;
        String type = p.utf8[u2(b, i)];
        int pairs = u2(b, i + 2);
        i += 4;
        for (int k = 0; k < pairs; k++) {
            if (i + 2 > b.length) return -1;
            String name = p.utf8[u2(b, i)];
            i += 2;
            List<String> vals = new ArrayList<>();
            i = value(p, b, i, vals, out);
            if (i < 0) return -1;
            if (MIXIN_ANNOTATION.equals(type) && ("value".equals(name) || "targets".equals(name))) {
                for (String v : vals) if (v != null) out.mixinTargets.add(v);
            }
        }
        return i;
    }

    private static int value(Pool p, byte[] b, int i, List<String> vals, Ann out) {
        if (i >= b.length) return -1;
        char tag = (char) (b[i++] & 0xFF);
        switch (tag) {
            case '[': {
                if (i + 2 > b.length) return -1;
                int n = u2(b, i);
                i += 2;
                for (int k = 0; k < n; k++) {
                    i = value(p, b, i, vals, out);
                    if (i < 0) return -1;
                }
                return i;
            }
            case '@':
                return annotation(p, b, i, out);
            case 'c': {
                if (i + 2 > b.length) return -1;
                String d = p.utf8[u2(b, i)];
                vals.add(descriptorClass(d));
                return i + 2;
            }
            case 's': {
                if (i + 2 > b.length) return -1;
                String s = p.utf8[u2(b, i)];
                if (s != null) vals.add(s.replace('.', '/'));
                return i + 2;
            }
            case 'e': {
                if (i + 4 > b.length) return -1;
                String type = p.utf8[u2(b, i)];
                String name = p.utf8[u2(b, i + 2)];
                if ("Lnet/fabricmc/api/EnvType;".equals(type) && "CLIENT".equals(name)) out.clientOnly = true;
                return i + 4;
            }
            default:
                return i + 2;
        }
    }

    private static String descriptorClass(String d) {
        if (d == null) return null;
        int i = 0;
        while (i < d.length() && d.charAt(i) == '[') i++;
        if (i < d.length() && d.charAt(i) == 'L' && d.endsWith(";")) return d.substring(i + 1, d.length() - 1);
        return null;
    }

    private static String simple(String internalName) {
        int i = internalName.lastIndexOf('/');
        return (i < 0 ? internalName : internalName.substring(i + 1)).replace('$', '.');
    }

    private static int u2(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    private static long u4(byte[] b, int i) {
        return ((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : java.util.Collections.emptyList();
    }

    private static String text(ZipFile z, String name) throws IOException {
        byte[] b = bytes(z, name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null || e.getSize() > 4 * 1024 * 1024) return null;
        try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
    }
}
