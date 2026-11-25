package openhouse.mod;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

final class MixinClash {
    private MixinClash() { }

    static final Pattern IN_CFG = Pattern.compile("in ([A-Za-z0-9_.\\-/]+\\.json):([A-Za-z0-9_.$]+) from mod ([A-Za-z0-9_\\-]+)");
    static final Pattern CALLBACK = Pattern.compile("(?:Callback method|Redirector|Injector|handler) ([A-Za-z0-9_$]+)\\(");

    static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";

    static Map<String, Set<String>> blame(List<String> lines, Map<String, List<File>> jars) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String l : lines) {
            if (l.contains("/WARN]") || !l.toLowerCase(java.util.Locale.ROOT).contains("injection")) continue;
            Matcher m = IN_CFG.matcher(l);
            if (!m.find()) continue;
            String cfg = m.group(1), mixin = m.group(2), victim = m.group(3);
            if (!jars.containsKey(victim) || !seen.add(victim + "|" + mixin)) continue;
            Matcher cb = CALLBACK.matcher(l);
            String callback = cb.find() ? cb.group(1) : null;
            MixinInfo vi = victimMixin(jars.get(victim), cfg, mixin);
            if (vi == null || vi.targets.isEmpty()) continue;
            Set<String> hooked = new HashSet<>();
            for (Meth mt : vi.meths) if (callback == null || callback.equals(mt.name)) hooked.addAll(mt.hooks);
            if (hooked.isEmpty()) continue;
            Set<String> strong = new LinkedHashSet<>(), weak = new LinkedHashSet<>();
            for (Map.Entry<String, List<File>> e : jars.entrySet()) {
                if (e.getKey().equals(victim)) continue;
                for (File jar : e.getValue()) for (MixinInfo oi : serverMixins(jar)) {
                    if (java.util.Collections.disjoint(oi.targets, vi.targets)) continue;
                    for (Meth mt : oi.meths) {
                        if (mt.overwrite && hooked.contains(mt.name)) strong.add(e.getKey());
                        else if (!java.util.Collections.disjoint(mt.hooks, hooked)) weak.add(e.getKey());
                    }
                }
            }
            Set<String> got = !strong.isEmpty() ? strong : weak;
            if (!got.isEmpty()) out.computeIfAbsent(victim, k -> new LinkedHashSet<>()).addAll(got);
        }
        return out;
    }

    static final class Meth {
        String name;
        boolean overwrite;
        final Set<String> hooks = new HashSet<>();
    }

    static final class MixinInfo {
        final List<String> targets = new ArrayList<>();
        final List<Meth> meths = new ArrayList<>();
    }

    private static MixinInfo victimMixin(List<File> jars, String cfg, String mixin) {
        for (File jar : jars) {
            try (ZipFile z = new ZipFile(jar)) {
                Map<String, Object> cj = json(ClientCode.text(z, cfg));
                if (cj == null) continue;
                String pkg = openhouse.Json.str(cj.get("package"));

                String cls = (pkg == null || mixin.startsWith(pkg + ".") ? mixin : pkg + "." + mixin).replace('.', '/');
                MixinInfo mi = read(ClientCode.bytes(z, cls + ".class"));
                if (mi != null) return mi;
            } catch (IOException | RuntimeException ignored) { }
        }
        return null;
    }

    private static final Map<File, List<MixinInfo>> cache = new HashMap<>();
    static synchronized List<MixinInfo> serverMixins(File jar) {
        List<MixinInfo> hit = cache.get(jar);
        if (hit != null) return hit;
        List<MixinInfo> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar)) {
            Map<String, Object> fmj = json(ClientCode.text(z, "fabric.mod.json"));
            if (fmj != null && !"client".equals(openhouse.Json.str(fmj.get("environment")))) {
                for (Object c : ClientCode.list(fmj.get("mixins"))) {
                    String name = openhouse.Json.str(c);
                    if (name == null) {
                        Map<String, Object> o = openhouse.Json.obj(c);
                        if (o == null || "client".equals(openhouse.Json.str(o.get("environment")))) continue;
                        name = openhouse.Json.str(o.get("config"));
                    }
                    Map<String, Object> cj = name == null ? null : json(ClientCode.text(z, name));
                    if (cj == null) continue;
                    String pkg = openhouse.Json.str(cj.get("package"));
                    pkg = pkg == null ? "" : pkg.replace('.', '/') + "/";
                    List<Object> all = new ArrayList<>(ClientCode.list(cj.get("mixins")));
                    all.addAll(ClientCode.list(cj.get("server")));
                    for (Object m : all) {
                        String mn = openhouse.Json.str(m);
                        if (mn == null) continue;
                        MixinInfo mi = read(ClientCode.bytes(z, pkg + mn.replace('.', '/') + ".class"));
                        if (mi != null) out.add(mi);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        cache.put(jar, out);
        return out;
    }

    private static Map<String, Object> json(String t) {
        if (t == null) return null;
        try { return openhouse.Json.obj(openhouse.Json.parse(t)); } catch (RuntimeException e) { return null; }
    }

    static MixinInfo read(byte[] b) {
        if (b == null) return null;
        ClientCode.Pool p = ClientCode.pool(b);
        if (p == null) return null;
        MixinInfo out = new MixinInfo();
        int i = p.body + 6;
        if (i + 2 > b.length) return null;
        i += 2 + 2 * ClientCode.u2(b, i);
        for (int pass = 0; pass < 2; pass++) {
            if (i + 2 > b.length) return null;
            int cnt = ClientCode.u2(b, i);
            i += 2;
            for (int k = 0; k < cnt; k++) {
                if (i + 8 > b.length) return null;
                Meth mt = new Meth();
                mt.name = p.utf8[ClientCode.u2(b, i + 2)];
                int ac = ClientCode.u2(b, i + 6);
                i += 8;
                for (int j = 0; j < ac; j++) {
                    if (i + 6 > b.length) return null;
                    String an = p.utf8[ClientCode.u2(b, i)];
                    int at = i + 6;
                    i = at + (int) ClientCode.u4(b, i + 2);
                    if (pass == 1 && isAnn(an)) anns(p, b, at, null, mt);
                }
                if (pass == 1 && (mt.overwrite || !mt.hooks.isEmpty())) out.meths.add(mt);
            }
        }
        if (i + 2 > b.length) return null;
        int ac = ClientCode.u2(b, i);
        i += 2;
        for (int j = 0; j < ac; j++) {
            if (i + 6 > b.length) return null;
            String an = p.utf8[ClientCode.u2(b, i)];
            int at = i + 6;
            i = at + (int) ClientCode.u4(b, i + 2);
            if (isAnn(an)) anns(p, b, at, out.targets, null);
        }
        return out;
    }

    private static boolean isAnn(String n) {
        return "RuntimeVisibleAnnotations".equals(n) || "RuntimeInvisibleAnnotations".equals(n);
    }

    private static void anns(ClientCode.Pool p, byte[] b, int at, List<String> targets, Meth mt) {
        if (at + 2 > b.length) return;
        int num = ClientCode.u2(b, at);
        int q = at + 2;
        for (int k = 0; k < num && q > 0; k++) q = ann(p, b, q, targets, mt, true);
    }

    private static int ann(ClientCode.Pool p, byte[] b, int i, List<String> targets, Meth mt, boolean top) {
        if (i + 4 > b.length) return -1;
        String type = p.utf8[ClientCode.u2(b, i)];
        int pairs = ClientCode.u2(b, i + 2);
        i += 4;
        boolean mixin = top && targets != null && "Lorg/spongepowered/asm/mixin/Mixin;".equals(type);
        boolean injector = top && mt != null && type != null && (type.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                || type.startsWith("Lcom/llamalad7/mixinextras/injector/"));
        if (top && mt != null && OVERWRITE.equals(type)) mt.overwrite = true;
        for (int k = 0; k < pairs; k++) {
            if (i + 2 > b.length) return -1;
            String name = p.utf8[ClientCode.u2(b, i)];
            List<String> vals = new ArrayList<>();
            i = val(p, b, i + 2, vals, targets, mt);
            if (i < 0) return -1;
            if (mixin && ("value".equals(name) || "targets".equals(name))) for (String v : vals) if (v != null) targets.add(v.replace('.', '/'));
            if (injector && "method".equals(name)) for (String v : vals) if (v != null) mt.hooks.add(methodName(v));
        }
        return i;
    }

    private static int val(ClientCode.Pool p, byte[] b, int i, List<String> vals, List<String> targets, Meth mt) {
        if (i >= b.length) return -1;
        char tag = (char) (b[i++] & 0xFF);
        switch (tag) {
            case '[': {
                if (i + 2 > b.length) return -1;
                int n = ClientCode.u2(b, i);
                i += 2;
                for (int k = 0; k < n && i > 0; k++) i = val(p, b, i, vals, targets, mt);
                return i;
            }
            case '@': return ann(p, b, i, targets, mt, false);
            case 'c': {
                if (i + 2 > b.length) return -1;
                vals.add(ClientCode.descriptorClass(p.utf8[ClientCode.u2(b, i)]));
                return i + 2;
            }
            case 's': {
                if (i + 2 > b.length) return -1;
                vals.add(p.utf8[ClientCode.u2(b, i)]);
                return i + 2;
            }
            case 'e': return i + 4;
            default: return i + 2;
        }
    }

    static String methodName(String sel) {
        int paren = sel.indexOf('(');
        String s = paren >= 0 ? sel.substring(0, paren) : sel;
        int semi = s.lastIndexOf(';');
        if (semi >= 0) s = s.substring(semi + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }
}
