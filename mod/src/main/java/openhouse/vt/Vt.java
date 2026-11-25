package openhouse.vt;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class Vt {
    static final String POSC = "Lnet/minecraft/core/world/pos/TilePosc;", POS = "Lnet/minecraft/core/world/pos/TilePos;";
    static final String GEN = "openhouse/v7/gen/";

    final Map<String, ClassNode> g7, shim;
    final Map<String, MethodNode> adapters = new LinkedHashMap<>();
    final String gen_class;
    final Map<String, Integer> unresolved = new TreeMap<>();
    int rewired, adapted;
    final List<Vt> nested = new ArrayList<>();
    final Map<String, ClassNode> own = new HashMap<>();

    String game_owner(String owner, String name, String desc, boolean method) {
        String o = owner;
        Set<String> seen = new HashSet<>();
        while (o != null && own.containsKey(o) && seen.add(o)) {
            ClassNode c = own.get(o);
            if (method) { for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return null; }
            else { for (FieldNode f : c.fields) if (f.name.equals(name) && f.desc.equals(desc)) return null; }

            if (own_itf_has(c, name, desc, method, seen)) return null;
            o = c.superName;
        }
        return o != null && game(o) ? o : null;
    }

    boolean own_itf_has(ClassNode c, String name, String desc, boolean method, Set<String> seen) {
        if (c.interfaces == null) return false;
        for (String i : c.interfaces) {
            ClassNode k = own.get(i);
            if (k == null || !seen.add(i)) continue;
            if (method) { for (MethodNode m : k.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true; }
            else { for (FieldNode f : k.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true; }
            if (own_itf_has(k, name, desc, method, seen)) return true;
        }
        return false;
    }

    static Map<String, ClassNode> G8 = new HashMap<>();
    static Map<String, ClassNode> G7;
    static Map<String, List<String>> SIMPLE;

    static Map<String, ClassNode> SHIM;
    static final Map<String, String> CLASS_RENAMES = new HashMap<>();
    static {
        CLASS_RENAMES.put("net/minecraft/client/render/EntityRendererDispatcher", "net/minecraft/client/render/EntityRenderDispatcher");

        CLASS_RENAMES.put("net/minecraft/core/net/packet/PacketHandshake", "net/minecraft/core/net/packet/PacketPreLogin");

        CLASS_RENAMES.put("net/minecraft/core/net/packet/PacketMessage", "net/minecraft/core/net/packet/PacketChat");
        CLASS_RENAMES.put("net/minecraft/core/net/packet/PacketPlaySoundDirect", "net/minecraft/core/net/packet/PacketPlaySoundEffectDirect");

        CLASS_RENAMES.put("net/minecraft/core/world/lighting/LightingEngineLegacy$LightUpdate", "net/minecraft/core/world/chunk/LightUpdate");

        CLASS_RENAMES.put("net/minecraft/core/world/chunk/provider/ChunkProvider", "net/minecraft/core/world/chunk/provider/IChunkProvider");
        CLASS_RENAMES.put("net/minecraft/core/world/chunk/ChunkLoader", "net/minecraft/core/world/chunk/IChunkLoader");
    }

    static Map<String, String> KIND_SWAP;
    static String kind_swap(String n) {
        if (KIND_SWAP == null) {
            if (G7 == null || SHIM == null) return null;
            if (G8.isEmpty()) load_g8_table();
            Map<String, String> m = new HashMap<>();
            for (ClassNode c8 : G8.values()) {
                ClassNode c7 = G7.get(c8.name);
                if (c7 == null || ((c7.access ^ c8.access) & Opcodes.ACC_INTERFACE) == 0) continue;
                String sw = "openhouse/v7/" + c8.name.substring(c8.name.lastIndexOf('/') + 1) + "8";
                if (SHIM.containsKey(sw)) m.put(c8.name, sw);
            }
            KIND_SWAP = m;
        }
        return KIND_SWAP.get(n);
    }

    static Map<String, String> WRAP_SWAP;
    static String wrap_swap(String n) {
        if (WRAP_SWAP == null) {
            if (G7 == null) return null;
            if (G8.isEmpty()) load_g8_table();
            Map<String, String> m = new HashMap<>();
            for (ClassNode c8 : G8.values()) {
                if (G7.containsKey(c8.name) || (c8.access & (Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE)) != Opcodes.ACC_FINAL) continue;
                if (!"java/lang/Object".equals(c8.superName) || c8.interfaces.size() != 1 || game(c8.interfaces.get(0))) continue;
                m.put(c8.name, c8.interfaces.get(0));
            }
            WRAP_SWAP = m;
        }
        return WRAP_SWAP.get(n);
    }

    static Map<String, String> FOUND_RENAMES;
    static synchronized Map<String, String> found_renames() {
        if (FOUND_RENAMES != null) return FOUND_RENAMES;
        Map<String, String> m = new HashMap<>();
        try (InputStream in = Vt.class.getResourceAsStream("/openhouse/v7/g8renames.txt")) {
            if (in != null) for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) { String[] t = l.trim().split(" "); if (t.length == 2) m.put(t[0], t[1]); }
        } catch (IOException ignored) { }
        return FOUND_RENAMES = m;
    }

    static String moved(String n) {
        String ks = game(n) ? kind_swap(n) : null;
        if (ks != null) return ks;
        String ws = game(n) ? wrap_swap(n) : null;
        if (ws != null) return ws;
        if (!game(n) || G7 == null || G7.containsKey(n)) return n;
        if (SHIM != null && SHIM.containsKey(n)) return n;
        String rn = CLASS_RENAMES.get(n);
        if (rn == null) rn = found_renames().get(n);
        if (rn != null && G7.containsKey(rn)) return rn;
        if (SIMPLE == null) {
            SIMPLE = new HashMap<>();

            for (String k : G7.keySet()) if (game(k)) SIMPLE.computeIfAbsent(k.substring(k.lastIndexOf('/') + 1), x -> new ArrayList<>()).add(k);
        }
        List<String> c = SIMPLE.get(n.substring(n.lastIndexOf('/') + 1));
        if (c == null || c.size() != 1 || n.contains("$") != c.get(0).contains("$")) return n;

        if (G8.isEmpty()) load_g8_table();
        ClassNode a = G8.get(n), b = G7.get(c.get(0));
        if (a != null && b != null && ((a.access ^ b.access) & Opcodes.ACC_INTERFACE) != 0) return n;
        return c.get(0);
    }

    Vt(Map<String, ClassNode> g7, Map<String, ClassNode> shim, String modid) {
        G7 = g7;
        SHIM = shim;
        this.g7 = g7;
        this.shim = shim;
        gen_class = GEN + "A_" + modid.replaceAll("[^A-Za-z0-9_]", "_");
        own.putAll(PACK);
    }

    static final Map<String, ClassNode> PACK = new HashMap<>();

    static void scan(byte[] jar) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) { scan(z.readAllBytes()); continue; }
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                ClassNode c = new ClassNode();
                new ClassReader(z.readAllBytes()).accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                if (!game(c.name)) PACK.put(c.name, c);
            }
        }
    }

    static Map<String, ClassNode> load(String jar) throws IOException {
        Map<String, ClassNode> m = new HashMap<>();
        if (jar.equals("-")) return m;
        try (ZipFile z = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.getName().endsWith(".class")) continue;
                ClassNode c = new ClassNode();
                new ClassReader(z.getInputStream(en)).accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                m.put(c.name, c);
            }
        }
        return m;
    }

    static boolean game(String o) { return o.startsWith("net/minecraft/") || o.startsWith("com/mojang/"); }
    boolean known_class(String o) { return !game(o) || g7.containsKey(o) || shim.containsKey(o); }

    String found_in;
    MethodNode find(String owner, String name, String desc) {
        found_in = owner;
        if (name.equals("<init>")) {
            ClassNode c = g7.containsKey(owner) ? g7.get(owner) : shim.get(owner);
            if (c == null) return game(owner) ? null : OBJ_ANY;
            for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
            return null;
        }
        Deque<String> q = new ArrayDeque<>(Collections.singleton(owner));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String o = q.poll();
            if (!seen.add(o)) continue;
            ClassNode c = g7.containsKey(o) ? g7.get(o) : shim.get(o);
            if (c == null) {

                if (!game(o) && JDK_NAMES.contains(name) && !(game(owner) && !o.equals("java/lang/Enum") && ENUM_NAMES.contains(name))) return OBJ_ANY;
                if (jdk_has(o, name, desc)) return OBJ_ANY;
                continue;
            }
            for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) { found_in = o; return m; }
            if (c.superName != null) q.add(c.superName);
            if (c.interfaces != null) q.addAll(c.interfaces);
        }
        return null;
    }
    static final MethodNode OBJ_ANY = new MethodNode();

    static boolean jdk_has(String owner, String name, String desc) {
        if (!owner.startsWith("java/")) return false;
        try {
            Class<?> k = Class.forName(owner.replace('/', '.'), false, null);
            for (Class<?> x = k; x != null; x = x.getSuperclass()) if (jdk_declares(x, name, desc)) return true;
            for (Class<?> i : k.getInterfaces()) if (jdk_declares(i, name, desc) || jdk_has(Type.getInternalName(i), name, desc)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    static boolean jdk_declares(Class<?> k, String name, String desc) {
        for (java.lang.reflect.Method m : k.getDeclaredMethods()) if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return true;
        return false;
    }

    final Set<String> widen = new TreeSet<>();
    static String AW_NS = "official";

    boolean reach(int acc, String owner, boolean super_call) {
        if ((acc & Opcodes.ACC_PUBLIC) != 0) return true;
        if ((acc & Opcodes.ACC_PRIVATE) != 0) return cur_class != null && cur_class.equals(owner);
        if (cur_class != null && same_pkg(cur_class, owner)) return true;

        return (acc & Opcodes.ACC_PROTECTED) != 0 && super_call && caller_ancestors().contains(owner);
    }

    List<String> caller_ancestors() {
        String o = cur_class;
        Set<String> seen = new HashSet<>();
        while (o != null && own.containsKey(o) && seen.add(o)) o = own.get(o).superName;
        return o == null ? Collections.emptyList() : ancestors(o);
    }

    void open_method(MethodNode m, String owner, boolean super_call) {
        if (m == null || m == OBJ_ANY || !game(owner) || reach(m.access, owner, super_call)) return;
        widen.add("accessible method " + owner + " " + m.name + " " + m.desc);

        if ((m.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == Opcodes.ACC_PRIVATE && !m.name.equals("<init>"))
            widen.add("extendable method " + owner + " " + m.name + " " + m.desc);
    }

    void open_field(FieldNode f, String owner, boolean put) {
        if (!game(owner)) return;
        if (!reach(f.access, owner, false)) widen.add("accessible field " + owner + " " + f.name + " " + f.desc);
        if (put && (f.access & Opcodes.ACC_FINAL) != 0 && (cur_class == null || !cur_class.equals(owner))) widen.add("mutable field " + owner + " " + f.name + " " + f.desc);
    }

    void write_widener(Map<String, byte[]> files) {
        if (!files.containsKey("fabric.mod.json")) return;
        String js = new String(files.get("fabric.mod.json"), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(accessWidener|classTweaker)\"\\s*:\\s*\"([^\"]+)\"").matcher(js);
        StringBuilder b;
        String path;
        if (m.find() && files.containsKey(m.group(2))) {
            path = m.group(2);
            String theirs = remap_widener(new String(files.get(path), StandardCharsets.UTF_8));
            b = new StringBuilder(theirs);
            if (b.length() > 0 && b.charAt(b.length() - 1) != '\n') b.append('\n');

            for (String l : theirs.split("\n")) {
                String[] t = l.trim().split("\\s+");
                if (t.length != 5 || !t[0].endsWith("accessible") || !t[1].equals("method")) continue;
                ClassNode k = g7.get(t[2]);
                if (k != null) for (MethodNode mm : k.methods)
                    if (mm.name.equals(t[3]) && mm.desc.equals(t[4]) && (mm.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == Opcodes.ACC_PRIVATE)
                        widen.add("extendable method " + t[2] + " " + t[3] + " " + t[4]);
            }
        } else {
            if (widen.isEmpty()) return;
            path = "openhouse-v7.accesswidener";
            b = new StringBuilder("accessWidener v2 " + AW_NS + "\n");
            String key = "\"accessWidener\": \"" + path + "\"";

            java.util.regex.Matcher sv = java.util.regex.Pattern.compile("\"schemaVersion\"\\s*:\\s*\\d+\\s*,").matcher(js);
            js = m.find(0) ? js.substring(0, m.start()) + key + js.substring(m.end())
                    : sv.find() ? js.substring(0, sv.end()) + "\n  " + key + "," + js.substring(sv.end())
                    : js.replaceFirst("\\{", java.util.regex.Matcher.quoteReplacement("{\n  " + key + ","));
            files.put("fabric.mod.json", js.getBytes(StandardCharsets.UTF_8));
        }
        for (String l : widen) b.append(l).append('\n');
        files.put(path, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String remap_widener(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:net/minecraft|com/mojang)/[A-Za-z0-9_/$]+").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(moved(m.group())));
        m.appendTail(b);
        return b.toString();
    }

    static final Map<String, String> RENAMES = new HashMap<>();
    static {
        RENAMES.put("getBlockData", "getBlockMetadata");
        RENAMES.put("getBlockType", "getBlock");
        RENAMES.put("setBlockDataNotify", "setBlockMetadataWithNotify");
        RENAMES.put("setBlockData", "setBlockMetadata");

        String[][] bl = { { "onInteracted", "onBlockRightClicked" }, { "onRemoved", "onBlockRemoved" }, { "onPlacedByMob", "onBlockPlacedByMob" },
                { "onPlacedByWorld", "onBlockPlacedByWorld" }, { "onPlacedOnSide", "onBlockPlacedOnSide" }, { "onDestroyedByPlayer", "onBlockDestroyedByPlayer" },
                { "onDestroyedByExplosion", "onBlockDestroyedByExplosion" }, { "canPlaceOnSide", "canPlaceBlockOnSide" }, { "canPlaceAt", "canPlaceBlockAt" },
                { "onActivatorInteracted", "onActivatorInteract" }, { "onEntityWalkedOn", "onEntityWalking" }, { "onAttacked", "onBlockLeftClicked" },
                { "isEmittingSignal", "getSignal" }, { "isEmittingDirectSignal", "getDirectSignal" }, { "onEntityCollision", "onEntityCollidedWithBlock" },
                { "onHarvest", "harvestBlock" }, { "dropWithCause", "dropBlockWithCause" }, { "canStay", "canBlockStay" }, { "getPlacedData", "getPlacedBlockMetadata" },
                { "getStrength", "blockStrength" }, { "getBrightness", "getBlockBrightness" }, { "onNeighborChanged", "onNeighborBlockChange" },
                { "onUse", "onUseItem" }, { "onUseOnBlock", "onUseItemOnBlock" } };
        for (String[] r : bl) RENAMES.put(r[0], r[1]);
        RENAMES.put("getBlockMaterial", "getBlockMaterial");

        RENAMES.put("getParticleTexture", "getBlockTextureFromSideAndMetadata");
        RENAMES.put("markBlockNeedsUpdate", "markBlockNeedsUpdate");
        RENAMES.put("getSavedLightValue", "getSavedLightValue");

        String[][] w = { { "setBlockType", "setBlock" }, { "setBlockTypeData", "setBlockAndMetadata" }, { "setBlockTypeDataNotify", "setBlockAndMetadataWithNotify" },
                { "setBlockTypeRaw", "setBlockRaw" }, { "setBlockTypeDataRaw", "setBlockAndMetadataRaw" }, { "canBlockIdBePlacedAt", "canBlockBePlacedAt" },
                { "canBlockSeeSky", "canBlockSeeTheSky" }, { "canLoadedBlockSeeSky", "canExistingBlockSeeTheSky" }, { "removeTileEntity", "removeBlockTileEntity" },
                { "hasSignal", "getSignal" }, { "isBlockBeingRainedOn", "canBlockBeRainedOn" } };
        for (String[] r : w) RENAMES.put(r[0], r[1]);
    }

    static String block_ids(String d) {
        int r = d.indexOf(')');
        String a = d.substring(0, r);
        return a.contains(BLOCK) ? a.replace(BLOCK, "I") + d.substring(r) : null;
    }
    static boolean wants_ids(String d8, String d7) {
        return d8.substring(0, d8.indexOf(')')).contains(BLOCK) && !d7.substring(0, d7.indexOf(')')).contains(BLOCK);
    }

    List<String> ancestors(String o) {
        List<String> out = new ArrayList<>();
        Deque<String> q = new ArrayDeque<>(Collections.singleton(o));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            out.add(c);
            ClassNode n = g7.containsKey(c) ? g7.get(c) : shim.get(c);
            if (n == null) continue;
            if (n.superName != null && game(n.superName)) q.add(n.superName);
            if (n.interfaces != null) for (String i : n.interfaces) if (game(i)) q.add(i);
        }
        return out;
    }
    static final Set<String> ENUM_NAMES = new HashSet<>(Arrays.asList("ordinal", "name", "compareTo", "values", "valueOf", "getDeclaringClass", "describeConstable"));
    static final Set<String> JDK_NAMES = new HashSet<>(Arrays.asList("equals", "hashCode", "toString", "getClass", "clone", "notify",
            "notifyAll", "wait", "finalize", "ordinal", "name", "compareTo", "values", "valueOf", "getDeclaringClass", "<init>", "describeConstable"));

    String cur_class;
    static boolean same_pkg(String a, String b) { return a.substring(0, Math.max(0, a.lastIndexOf('/'))).equals(b.substring(0, Math.max(0, b.lastIndexOf('/')))); }

    FieldNode field_node(String owner, String name, String desc) {
        Deque<String> q = new ArrayDeque<>(Collections.singleton(owner));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String o = q.poll();
            if (!seen.add(o)) continue;
            ClassNode c = g7.containsKey(o) ? g7.get(o) : shim.get(o);
            if (c == null) continue;
            for (FieldNode f : c.fields) if (f.name.equals(name) && f.desc.equals(desc)) { field_in = o; return f; }
            if (c.superName != null) q.add(c.superName);
            if (c.interfaces != null) q.addAll(c.interfaces);
        }
        return null;
    }
    String field_in;

    boolean has_field(String owner, String name, String desc) {
        Deque<String> q = new ArrayDeque<>(Collections.singleton(owner));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String o = q.poll();
            if (!seen.add(o)) continue;
            ClassNode c = g7.containsKey(o) ? g7.get(o) : shim.get(o);
            if (c == null) continue;
            for (FieldNode f : c.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
            if (c.superName != null) q.add(c.superName);
            if (c.interfaces != null) q.addAll(c.interfaces);
        }
        return false;
    }

    void translate(String in, String out) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(in)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (en.isDirectory()) continue;
                files.put(en.getName(), z.getInputStream(en).readAllBytes());
            }
        }
        Set<String> mixin_configs = new HashSet<>();
        if (files.containsKey("fabric.mod.json")) entrypoints(new String(files.get("fabric.mod.json"), StandardCharsets.UTF_8));
        if (files.containsKey("fabric.mod.json")) files.put("fabric.mod.json", mod_json(files.get("fabric.mod.json"), mixin_configs));
        for (String mc : mixin_configs) if (files.containsKey(mc)) files.put(mc, mixin_json(files.get(mc)));
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!e.getKey().endsWith(".class")) continue;
            ClassNode c = new ClassNode();
            new ClassReader(e.getValue()).accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            own.put(c.name, c);
        }
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getKey().startsWith("META-INF/jars/") && e.getKey().endsWith(".jar")) {

                File ti = File.createTempFile("vtj", ".jar"), to = File.createTempFile("vtj", ".jar");
                java.nio.file.Files.write(ti.toPath(), e.getValue());

                Vt sub = new Vt(g7, shim, nested_id(ti, e.getKey()));
                sub.translate(ti.getPath(), to.getPath());
                nested.add(sub);
                e.setValue(java.nio.file.Files.readAllBytes(to.toPath()));
                ti.delete(); to.delete();
                continue;
            }
            if (!e.getKey().endsWith(".class")) continue;
            e.setValue(clazz(e.getValue()));
        }
        if (!adapters.isEmpty() || !stubs.isEmpty()) files.put(gen_class + ".class", gen());
        write_widener(files);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (e.getKey().startsWith("META-INF/") && (e.getKey().endsWith(".SF") || e.getKey().endsWith(".RSA") || e.getKey().endsWith(".DSA"))) continue;
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
    }

    final Set<String> entry = new HashSet<>();
    void entrypoints(String js) {
        int at = js.indexOf("\"entrypoints\"");
        if (at < 0) return;
        int open = js.indexOf('{', at), depth = 0, end = -1;
        if (open < 0) return;
        for (int i = open; i < js.length(); i++) {
            if (js.charAt(i) == '{') depth++;
            else if (js.charAt(i) == '}' && --depth == 0) { end = i; break; }
        }
        if (end < 0) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)(?:::[\\w$]+)?\"").matcher(js.substring(open, end));
        while (m.find()) entry.add(m.group(1).replace('.', '/'));
    }

    static String nested_id(File jar, String path) {
        try (ZipFile z = new ZipFile(jar)) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                if (m.find()) return m.group(1);
            }
        } catch (IOException ignored) { }
        return path.replaceAll(".*/", "").replaceAll("\\.jar$", "");
    }

    byte[] mod_json(byte[] b, Set<String> configs) {
        String s = new String(b, StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"mixins\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(s);
        if (m.find()) {
            java.util.regex.Matcher q = java.util.regex.Pattern.compile("\"([^\"]+\\.json)\"|\"config\"\\s*:\\s*\"([^\"]+)\"").matcher(m.group(1));
            while (q.find()) configs.add(q.group(1) != null ? q.group(1) : q.group(2));
        }

        s = s.replaceAll("(\"(?:minecraft|bta|fabricloader)\"\\s*:\\s*)(\"[^\"]*\"|\\[[^\\]]*\\])", "$1\"*\"");
        return s.getBytes(StandardCharsets.UTF_8);
    }

    byte[] mixin_json(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        s = s.replaceAll("\"required\"\\s*:\\s*true", "\"required\": false");
        s = s.replaceAll("\"defaultRequire\"\\s*:\\s*\\d+", "\"defaultRequire\": 0");
        s = s.replaceAll("\"compatibilityLevel\"\\s*:\\s*\"JAVA_\\d+\"", "\"compatibilityLevel\": \"JAVA_17\"");
        return s.getBytes(StandardCharsets.UTF_8);
    }

    byte[] clazz(byte[] b) {
        ClassNode c = new ClassNode();
        Set<String> refs = new HashSet<>();

        new ClassReader(b).accept(new org.objectweb.asm.commons.ClassRemapper(c, new org.objectweb.asm.commons.Remapper() {
            @Override public String map(String n) {
                String r = shim.containsKey(n) ? n : moved(n);
                if (game(r)) refs.add(r);
                else if (r.indexOf('/') > 0) LIB_REFS.add(r.substring(0, r.lastIndexOf('/')));
                return r;
            }
        }), 0);
        boolean mixin = is_mixin(c);
        cur_class = c.name;
        if (mixin) for (List<AnnotationNode> l : Arrays.asList(c.visibleAnnotations, c.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l)
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") && a.values != null) for (int i = 0; i < a.values.size(); i += 2)
                if (a.values.get(i).equals("value") && a.values.get(i + 1) instanceof List && !((List<?>) a.values.get(i + 1)).isEmpty())
                    cur_class = ((Type) ((List<?>) a.values.get(i + 1)).get(0)).getInternalName();
        if (c.superName != null && game(c.superName) && !known_class(c.superName)) { stub(c.superName).methods.put("<init>()V", Opcodes.INVOKESPECIAL); unresolved.merge("class " + c.superName, 1, Integer::sum); }
        for (String it : c.interfaces) if (game(it) && !known_class(it)) { stub(it).itf = true; unresolved.merge("class " + it, 1, Integer::sum); }

        for (String r : refs) {
            if (!known_class(r)) { if (!mixin) note_class(r, c.name); continue; }
            ClassNode k = g7.get(r);
            if (k != null && (k.access & Opcodes.ACC_PUBLIC) == 0 && !same_pkg(cur_class, r)) widen.add("accessible class " + r);
        }
        for (MethodNode m : c.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in instanceof MethodInsnNode) call(m, (MethodInsnNode) in, c.name);
                else if (in instanceof FieldInsnNode) {
                    AbstractInsnNode r = field((FieldInsnNode) in, c.name);
                    if (r != null) m.instructions.set(in, r);
                }
                else if (in instanceof TypeInsnNode) {
                    TypeInsnNode t = (TypeInsnNode) in;
                    note_class(t.desc, c.name);

                    if (t.getOpcode() == Opcodes.CHECKCAST && t.desc.startsWith("[L") && game(Type.getType(t.desc).getElementType().getInternalName())) {
                        InsnList il = new InsnList();
                        il.add(new LdcInsnNode(Type.getType(t.desc)));
                        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Arr", "as", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false));
                        m.instructions.insertBefore(t, il);
                        m.maxStack += 1;
                    }
                }
                else if (in instanceof InvokeDynamicInsnNode) lambda((InvokeDynamicInsnNode) in);
            }
        }
        bridges(c);
        if (!mixin) bridges2(c);
        if (!mixin) abstract_net(c);
        boolean frames = frames_needed;
        frames_needed = false;
        if (mixin) { soften(c); frames |= wrap(c); }
        else if (entry.contains(c.name)) frames |= wrap(c, true);
        ClassWriter w = frames ? new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {

            @Override protected String getCommonSuperClass(String a, String b) { return common(a, b); }
        } : new ClassWriter(0);
        c.accept(w);
        return w.toByteArray();
    }

    void bridges(ClassNode c) {
        String sup = c.superName;
        while (sup != null && own.containsKey(sup)) sup = own.get(sup).superName;
        if (sup == null || !game(sup) || !g7.containsKey(sup)) return;
        List<MethodNode> add = new ArrayList<>();
        for (MethodNode m : c.methods) {
            if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.name.startsWith("<")) continue;
            if (find(sup, m.name, m.desc) != null || hook_handler(m)) continue;
            Type[] mine = Type.getArgumentTypes(m.desc);
            for (String anc : ancestors(sup)) {
                ClassNode ac = g7.get(anc);
                if (ac == null) continue;
                for (MethodNode t : ac.methods) {
                    if (!t.name.equals(m.name) || (t.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) continue;
                    if (!Type.getReturnType(t.desc).equals(Type.getReturnType(m.desc))) continue;
                    Type[] theirs = Type.getArgumentTypes(t.desc);
                    if (theirs.length == mine.length + 1) {
                        MethodNode b = fewer(c, m, t, mine, theirs, add);
                        if (b != null) { add.add(guarded(c, b)); rewired++; }
                        continue;
                    }
                    if (theirs.length != mine.length - 1) continue;
                    int drop = -1;
                    for (int d = 0; d < mine.length && drop < 0; d++) {
                        if (mine[d].getSort() != Type.OBJECT) continue;
                        boolean ok = true;
                        for (int i = 0, j = 0; i < mine.length && ok; i++) { if (i == d) continue; ok = mine[i].equals(theirs[j++]); }
                        if (ok) drop = d;
                    }
                    if (drop < 0) continue;
                    boolean have = false;
                    for (MethodNode e : c.methods) if (e.name.equals(t.name) && e.desc.equals(t.desc)) have = true;
                    for (MethodNode e : add) if (e.name.equals(t.name) && e.desc.equals(t.desc)) have = true;
                    if (have) continue;
                    MethodNode b = new MethodNode(Opcodes.ACC_PUBLIC, t.name, t.desc, null, null);
                    b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    int slot = 1;
                    for (int i = 0, j = 0; i < mine.length; i++) {
                        if (i == drop) { own_field_or_null(b.instructions, c, sup, mine[i]); continue; }
                        b.instructions.add(new VarInsnNode(theirs[j].getOpcode(Opcodes.ILOAD), slot));
                        slot += theirs[j++].getSize();
                    }
                    b.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.name, m.name, m.desc, false));
                    b.instructions.add(new InsnNode(Type.getReturnType(t.desc).getOpcode(Opcodes.IRETURN)));
                    b.maxLocals = slot;
                    b.maxStack = slot + 2;
                    add.add(guarded(c, b));
                    rewired++;
                }
            }
        }
        c.methods.addAll(add);
    }

    void own_field_or_null(InsnList il, ClassNode c, String sup, Type want) {
        String from = is_mixin(c) && cur_class != null && g7.containsKey(cur_class) ? cur_class : sup;
        FieldNode one = null;
        String in = null;
        int n = 0;
        if (want.getSort() == Type.OBJECT)
            for (String o = from; o != null && g7.containsKey(o); o = g7.get(o).superName)
                for (FieldNode f : g7.get(o).fields)
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.startsWith("L") && sub_type(Type.getType(f.desc).getInternalName(), want.getInternalName())) { one = f; in = o; n++; }
        if (n != 1) { il.add(new InsnNode(Opcodes.ACONST_NULL)); return; }
        open_field(one, in, false);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, in, one.name, one.desc));
    }

    MethodNode guarded(ClassNode c, MethodNode b) {
        LabelNode s0 = new LabelNode(), s1 = new LabelNode(), h = new LabelNode();
        b.instructions.insert(s0);
        b.instructions.add(s1);
        b.instructions.add(h);
        b.instructions.add(new LdcInsnNode(c.name.substring(c.name.lastIndexOf('/') + 1) + "." + b.name + " (8.0 override)"));
        b.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Miss", "caught", "(Ljava/lang/Throwable;Ljava/lang/String;)V", false));
        Type r = Type.getReturnType(b.desc);

        String so = super_with(c, b.name, b.desc);
        if (so != null && r.getSort() != Type.VOID) {
            b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            int slot = 1;
            for (Type a : Type.getArgumentTypes(b.desc)) { b.instructions.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot)); slot += a.getSize(); }
            b.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, so, b.name, b.desc, false));
        } else switch (r.getSort()) {
            case Type.VOID: break;
            case Type.LONG: b.instructions.add(new InsnNode(Opcodes.LCONST_0)); break;
            case Type.FLOAT: b.instructions.add(new InsnNode(Opcodes.FCONST_0)); break;
            case Type.DOUBLE: b.instructions.add(new InsnNode(Opcodes.DCONST_0)); break;
            case Type.OBJECT: case Type.ARRAY: b.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); break;
            default: b.instructions.add(new InsnNode(Opcodes.ICONST_0));
        }
        b.instructions.add(new InsnNode(r.getOpcode(Opcodes.IRETURN)));
        b.tryCatchBlocks.add(new TryCatchBlockNode(s0, s1, h, "java/lang/Throwable"));
        b.maxStack = Math.max(b.maxStack, Type.getArgumentsAndReturnSizes(b.desc) + 4);
        frames_needed = true;
        return b;
    }

    String super_with(ClassNode c, String name, String desc) {
        String so = c.superName;
        if (is_mixin(c) && (so == null || !game(so)) && cur_class != null && g7.containsKey(cur_class)) so = g7.get(cur_class).superName;
        if (so == null) return null;
        String g = so;
        Set<String> seen = new HashSet<>();
        while (g != null && own.containsKey(g) && seen.add(g)) g = own.get(g).superName;
        if (g == null || !g7.containsKey(g)) return null;
        MethodNode m = find(g, name, desc);
        if (m == null || m == OBJ_ANY || (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) return null;
        ClassNode in = g7.get(found_in);
        if (in == null || (in.access & Opcodes.ACC_INTERFACE) != 0) return null;
        open_method(m, found_in, true);
        return so;
    }

    MethodNode fewer(ClassNode c, MethodNode m, MethodNode t, Type[] mine, Type[] theirs, List<MethodNode> add) {
        if (is_mixin(c)) return null;
        int drop = -1;
        for (int d = 0; d < theirs.length && drop < 0; d++) {
            if (theirs[d].getSort() != Type.OBJECT) continue;
            boolean ok = true;
            for (int i = 0, j = 0; i < theirs.length && ok; i++) { if (i == d) continue; ok = theirs[i].equals(mine[j++]); }
            if (ok) drop = d;
        }
        if (drop < 0) return null;
        for (MethodNode e : c.methods) if (e.name.equals(t.name) && e.desc.equals(t.desc)) return null;
        for (MethodNode e : add) if (e.name.equals(t.name) && e.desc.equals(t.desc)) return null;
        MethodNode b = new MethodNode(Opcodes.ACC_PUBLIC, t.name, t.desc, null, null);
        b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (int i = 0; i < theirs.length; i++) {
            if (i != drop) b.instructions.add(new VarInsnNode(theirs[i].getOpcode(Opcodes.ILOAD), slot));
            slot += theirs[i].getSize();
        }
        b.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.name, m.name, m.desc, false));
        b.instructions.add(new InsnNode(Type.getReturnType(t.desc).getOpcode(Opcodes.IRETURN)));
        b.maxLocals = slot;
        b.maxStack = slot + 2;
        return b;
    }

    static boolean hook_handler(MethodNode m) {
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) if (l != null)
            for (AnnotationNode a : l) if (a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") || a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) return true;
        return m.desc.contains("Lorg/spongepowered/asm/mixin/injection/callback/");
    }

    static final String TESS8 = "Lnet/minecraft/client/render/tessellator/TessellatorGeneral;", TESS7 = "Lnet/minecraft/client/render/tessellator/Tessellator;",
            BLOCK = "Lnet/minecraft/core/block/Block;", NBT = "(Lcom/mojang/nbt/tags/CompoundTag;)V";

    void bridges2(ClassNode c) {
        String sup = c.superName;
        while (sup != null && own.containsKey(sup)) sup = own.get(sup).superName;
        if (sup == null || !game(sup) || !g7.containsKey(sup)) return;
        List<MethodNode> add = new ArrayList<>();
        for (MethodNode m : new ArrayList<>(c.methods)) {
            if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.name.startsWith("<")) continue;
            if (find(sup, m.name, m.desc) != null) continue;
            String n7 = RENAMES.getOrDefault(m.name, m.name);
            Type[] mine = Type.getArgumentTypes(m.desc);
            Type ret = Type.getReturnType(m.desc);
            for (String anc : ancestors(sup)) {
                ClassNode ac = g7.get(anc);
                if (ac == null) continue;
                for (MethodNode t : ac.methods) {
                    if (!t.name.equals(n7) || (t.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) continue;

                    Type r7 = Type.getReturnType(t.desc);
                    if (!r7.equals(ret) && !(r7.getSort() == Type.OBJECT && ret.getSort() == Type.OBJECT && sub_type(ret.getInternalName(), r7.getInternalName()))) continue;
                    if (t.name.equals(m.name) && t.desc.equals(m.desc)) continue;
                    boolean have = false;
                    for (MethodNode e : c.methods) if (e.name.equals(t.name) && e.desc.equals(t.desc)) have = true;
                    for (MethodNode e : add) if (e.name.equals(t.name) && e.desc.equals(t.desc)) have = true;
                    if (have) continue;
                    MethodNode b = convert_bridge(c, m, t, mine, Type.getArgumentTypes(t.desc));
                    if (b == null) b = by_type(c, m, t, mine, Type.getArgumentTypes(t.desc));
                    if (b != null) { add.add(guarded(c, b)); rewired++; }
                }
            }
        }

        if (!own.containsKey(c.superName) && ancestors(sup).contains("net/minecraft/core/block/entity/TileEntity")) {
            for (String[] rw : new String[][] { { "readAdditionalData", "readFromNBT" }, { "writeAdditionalData", "writeToNBT" } }) {
                boolean mod_has = false, has7 = false;
                for (MethodNode e : c.methods) { if (e.name.equals(rw[0]) && e.desc.equals(NBT)) mod_has = true; if (e.name.equals(rw[1]) && e.desc.equals(NBT)) has7 = true; }
                if (!mod_has || has7) continue;
                MethodNode b = new MethodNode(Opcodes.ACC_PUBLIC, rw[1], NBT, null, null);
                b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                b.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, c.superName, rw[1], NBT, false));
                b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                b.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.name, rw[0], NBT, false));
                b.instructions.add(new InsnNode(Opcodes.RETURN));
                b.maxLocals = 2; b.maxStack = 2;
                add.add(b);
                rewired++;
            }
        }
        c.methods.addAll(add);
    }

    void abstract_net(ClassNode c) {
        if ((c.access & Opcodes.ACC_INTERFACE) != 0 || c.superName == null || own.containsKey(c.superName)) return;

        boolean game_itf = false;
        for (String i : c.interfaces) game_itf |= g7.containsKey(i);
        if (!g7.containsKey(c.superName) && !game_itf) return;
        Set<String> have = new HashSet<>();
        for (MethodNode m : c.methods) have.add(m.name + m.desc);
        List<String> chain = ancestors(c.superName);
        for (String i : c.interfaces) if (g7.containsKey(i)) chain.addAll(ancestors(i));
        for (String a : chain) {
            ClassNode k = g7.get(a);
            if (k == null || (k.access & Opcodes.ACC_INTERFACE) != 0) continue;
            for (MethodNode m : k.methods) if ((m.access & Opcodes.ACC_ABSTRACT) == 0 && (m.access & Opcodes.ACC_STATIC) == 0) have.add(m.name + m.desc);
        }
        for (String a : chain) {
            ClassNode k = g7.get(a);
            if (k == null) continue;
            for (MethodNode m : k.methods) {
                if ((m.access & Opcodes.ACC_ABSTRACT) == 0 || !have.add(m.name + m.desc)) continue;
                MethodNode d = new MethodNode(Opcodes.ACC_PUBLIC, m.name, m.desc, null, null);

                String alt = null;
                for (Map.Entry<String, String> r : RENAMES.entrySet())
                    if (r.getValue().equals(m.name) && !r.getKey().equals(m.name)) for (MethodNode e : c.methods) if (e.name.equals(r.getKey()) && e.desc.equals(m.desc) && (e.access & Opcodes.ACC_STATIC) == 0) alt = e.name;
                if (alt != null) {
                    d.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    int slot = 1;
                    for (Type at : Type.getArgumentTypes(m.desc)) { d.instructions.add(new VarInsnNode(at.getOpcode(Opcodes.ILOAD), slot)); slot += at.getSize(); }
                    d.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.name, alt, m.desc, false));
                    d.instructions.add(new InsnNode(Type.getReturnType(m.desc).getOpcode(Opcodes.IRETURN)));
                    d.maxStack = slot + 1;
                    d.maxLocals = slot;
                    c.methods.add(d);
                    rewired++;
                    continue;
                }
                miss(d, c.name + "." + m.name + " (7.3's, the mod has no such)");
                dflt(d, Type.getReturnType(m.desc));
                d.maxStack = 2;
                d.maxLocals = Type.getArgumentsAndReturnSizes(m.desc) >> 2;
                c.methods.add(d);
                stubbed++;
            }
        }
    }

    MethodNode convert_bridge(ClassNode c, MethodNode m, MethodNode t, Type[] mine, Type[] theirs) {
        int[] at = new int[theirs.length];
        int slot = 1;
        for (int j = 0; j < theirs.length; j++) { at[j] = slot; slot += theirs[j].getSize(); }
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int j = 0;
        boolean converted = false;
        for (Type a : mine) {
            String d = a.getDescriptor();
            if ((d.equals(POS) || d.equals(POSC)) && j + 2 < theirs.length && theirs[j].getSort() == Type.INT && theirs[j + 1].getSort() == Type.INT && theirs[j + 2].getSort() == Type.INT) {
                il.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/core/world/pos/TilePos"));
                il.add(new InsnNode(Opcodes.DUP));
                for (int q = 0; q < 3; q++) il.add(new VarInsnNode(Opcodes.ILOAD, at[j + q]));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/core/world/pos/TilePos", "<init>", "(III)V", false));
                j += 3; converted = true;
            } else if (d.equals(TESS8) && j < theirs.length && theirs[j].getDescriptor().equals(TESS7)) {
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/render/renderer/GLRenderer", "getTessellator", "()Lnet/minecraft/client/render/tessellator/TessellatorShader;", false));
                j++; converted = true;
            } else if (d.equals(BLOCK) && j < theirs.length && theirs[j].getSort() == Type.INT) {
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/core/block/Blocks", "blocksList", "[Lnet/minecraft/core/block/Block;"));
                il.add(new VarInsnNode(Opcodes.ILOAD, at[j]));
                il.add(new InsnNode(Opcodes.AALOAD));
                j++; converted = true;
            } else if (a.getSort() == Type.BOOLEAN && j == theirs.length) {
                il.add(new InsnNode(Opcodes.ICONST_0));
                converted = true;
            } else if (a.getSort() == Type.INT && j == theirs.length) {

                int ws = -1, xs = -1;
                for (int q = 0; q < theirs.length; q++) {
                    String td = theirs[q].getDescriptor();
                    if (ws < 0 && (td.equals("Lnet/minecraft/core/world/WorldSource;") || td.equals("Lnet/minecraft/core/world/World;"))) ws = q;
                    if (ws >= 0 && xs < 0 && q + 2 < theirs.length && theirs[q].getSort() == Type.INT && theirs[q + 1].getSort() == Type.INT && theirs[q + 2].getSort() == Type.INT) xs = q;
                }
                if (ws >= 0 && xs >= 0) {
                    boolean w = theirs[ws].getDescriptor().equals("Lnet/minecraft/core/world/World;");
                    il.add(new VarInsnNode(Opcodes.ALOAD, at[ws]));
                    for (int q = 0; q < 3; q++) il.add(new VarInsnNode(Opcodes.ILOAD, at[xs + q]));
                    il.add(new MethodInsnNode(w ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKEINTERFACE, w ? "net/minecraft/core/world/World" : "net/minecraft/core/world/WorldSource", "getBlockMetadata", "(III)I", !w));
                } else il.add(new InsnNode(Opcodes.ICONST_0));
                converted = true;
            } else if (j < theirs.length && (theirs[j].equals(a) || a.getSort() == Type.OBJECT && theirs[j].getSort() == Type.OBJECT && assignable(theirs[j].getInternalName(), a.getInternalName()))) {
                il.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), at[j]));
                if (!theirs[j].equals(a)) il.add(new TypeInsnNode(Opcodes.CHECKCAST, a.getInternalName()));
                j++;
            } else return null;
        }
        if (j != theirs.length || !converted && t.name.equals(m.name) && Type.getReturnType(t.desc).equals(Type.getReturnType(m.desc))) return null;
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.name, m.name, m.desc, false));
        il.add(new InsnNode(Type.getReturnType(t.desc).getOpcode(Opcodes.IRETURN)));
        MethodNode b = new MethodNode(Opcodes.ACC_PUBLIC, t.name, t.desc, null, null);
        b.instructions = il;
        b.maxLocals = slot;
        b.maxStack = slot + 6;
        return b;
    }

    MethodNode by_type(ClassNode c, MethodNode m, MethodNode t, Type[] mine, Type[] theirs) {
        int[] at = new int[theirs.length];
        int slot = 1;
        for (int j = 0; j < theirs.length; j++) { at[j] = slot; slot += theirs[j].getSize(); }
        boolean[] used = new boolean[theirs.length];
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (Type a : mine) {
            String d = a.getDescriptor();
            int pick = -1;
            if (d.equals(POS) || d.equals(POSC)) {
                for (int j = 0; j + 2 < theirs.length && pick < 0; j++)
                    if (!used[j] && !used[j + 1] && !used[j + 2] && theirs[j].getSort() == Type.INT && theirs[j + 1].getSort() == Type.INT && theirs[j + 2].getSort() == Type.INT) pick = j;
                if (pick < 0) return null;
                il.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/core/world/pos/TilePos"));
                il.add(new InsnNode(Opcodes.DUP));
                for (int q = 0; q < 3; q++) { il.add(new VarInsnNode(Opcodes.ILOAD, at[pick + q])); used[pick + q] = true; }
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/core/world/pos/TilePos", "<init>", "(III)V", false));
                continue;
            }
            if (a.getSort() == Type.OBJECT || a.getSort() == Type.ARRAY) {
                int n = 0;
                for (int j = 0; j < theirs.length; j++)
                    if (!used[j] && (theirs[j].equals(a) || a.getSort() == Type.OBJECT && theirs[j].getSort() == Type.OBJECT && sub_type(theirs[j].getInternalName(), a.getInternalName()))) { if (pick < 0) pick = j; n++; }
                if (n != 1) return null;
            } else for (int j = 0; j < theirs.length && pick < 0; j++) if (!used[j] && theirs[j].equals(a)) pick = j;
            if (pick < 0) return null;
            used[pick] = true;
            il.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), at[pick]));
            if (!theirs[pick].equals(a)) il.add(new TypeInsnNode(Opcodes.CHECKCAST, a.getInternalName()));
        }
        for (boolean u : used) if (!u) return null;
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.name, m.name, m.desc, false));
        il.add(new InsnNode(Type.getReturnType(t.desc).getOpcode(Opcodes.IRETURN)));
        MethodNode b = new MethodNode(Opcodes.ACC_PUBLIC, t.name, t.desc, null, null);
        b.instructions = il;
        b.maxLocals = slot;
        b.maxStack = slot + 6;
        return b;
    }

    boolean assignable(String from, String to) {
        if (from.equals(to)) return true;
        if (ancestors(from).contains(to)) return true;
        return ancestors(to).contains(from);
    }

    void lambda(InvokeDynamicInsnNode x) {
        if (x.bsm == null || !x.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || x.bsmArgs.length < 1) return;
        if (x.bsmArgs.length >= 2 && x.bsmArgs[1] instanceof Handle) x.bsmArgs[1] = method_ref((Handle) x.bsmArgs[1]);
        String itf = Type.getReturnType(x.desc).getInternalName();
        if (!game(itf) || known_class(itf)) return;
        Stub st = stub(itf);
        st.itf = true;
        st.abstracts.add(x.name + ((Type) x.bsmArgs[0]).getDescriptor());
        unresolved.merge("class " + itf, 1, Integer::sum);
    }

    Handle method_ref(Handle h) {
        int k = h.getTag();
        if (k != Opcodes.H_INVOKESTATIC && k != Opcodes.H_INVOKEVIRTUAL && k != Opcodes.H_INVOKEINTERFACE) return h;
        if (!game(h.getOwner()) || known_class(h.getOwner()) && find(h.getOwner(), h.getName(), h.getDesc()) != null) return h;
        if (!known_class(h.getOwner())) return h;
        boolean stat = k == Opcodes.H_INVOKESTATIC;
        String d = stat ? h.getDesc() : "(L" + h.getOwner() + ";" + h.getDesc().substring(1);
        String key = "h " + h.getOwner() + "." + h.getName() + d;
        MethodNode g = stubs.get(key);
        if (g == null) {
            g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "h" + stubs.size() + "_" + h.getName(), d, null, null);
            int slot = 0;
            for (Type a : Type.getArgumentTypes(d)) { g.instructions.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot)); slot += a.getSize(); }
            MethodInsnNode c = new MethodInsnNode(stat ? Opcodes.INVOKESTATIC : k == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, h.getOwner(), h.getName(), h.getDesc(), h.isInterface());
            g.instructions.add(c);
            g.instructions.add(new InsnNode(Type.getReturnType(d).getOpcode(Opcodes.IRETURN)));
            g.maxLocals = slot;
            g.maxStack = slot + 8;
            stubs.put(key, g);
            call(g, c, gen_class);
            g.maxLocals = Math.max(g.maxLocals, slot);
        }
        rewired++;
        return new Handle(Opcodes.H_INVOKESTATIC, gen_class, g.name, g.desc, false);
    }

    void note_class(String o, String where) {
        if (o.startsWith("[")) return;
        if (!known_class(o)) { unresolved.merge("class " + o, 1, Integer::sum); stub(o); }
    }

    static String camel(String n) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : n.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return b.toString();
    }

    static final Set<String> LIB_REFS = new TreeSet<>();
    static final class Stub { boolean itf; final Set<String> abstracts = new TreeSet<>(); final Map<String, Integer> methods = new TreeMap<>(); final Map<String, Boolean> fields = new TreeMap<>(); }
    static final Map<String, Stub> STUBS = new TreeMap<>();
    static Stub stub(String o) { return STUBS.computeIfAbsent(o, k -> new Stub()); }

    int stubbed;
    final Map<String, MethodNode> stubs = new LinkedHashMap<>();

    void unpack_inline(MethodNode in_m, MethodInsnNode x, String n7, String d7) {
        Type[] args = Type.getArgumentTypes(x.desc);
        int base = in_m.maxLocals, size = 0;
        int[] at = new int[args.length];
        for (int i = 0; i < args.length; i++) { at[i] = base + size; size += args[i].getSize(); }
        InsnList il = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), at[i]));
        boolean ids = wants_ids(x.desc, d7);
        for (int i = 0; i < args.length - (drops_last(x.desc, d7) ? 1 : 0); i++) {
            String d = args[i].getDescriptor();
            if (d.equals(POS) || d.equals(POSC)) {
                for (String c : new String[] { "x", "y", "z" }) {
                    il.add(new VarInsnNode(Opcodes.ALOAD, at[i]));
                    il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/core/world/pos/TilePosc", c, "()I", true));
                }
            } else if (ids && d.equals(BLOCK)) {
                il.add(new VarInsnNode(Opcodes.ALOAD, at[i]));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/SBlock", "id_of", "(Lnet/minecraft/core/block/Block;)I", false));
            } else il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), at[i]));
        }
        in_m.instructions.insertBefore(x, il);
        in_m.maxLocals += size;
        in_m.maxStack += 4;
        x.name = n7;
        x.desc = d7;
    }

    MethodNode stub_call(MethodInsnNode x) {
        boolean stat = x.getOpcode() == Opcodes.INVOKESTATIC;
        String sd = stat ? x.desc : "(L" + x.owner + ";" + x.desc.substring(1);
        String key = "m " + x.owner + "." + x.name + sd;
        MethodNode have = stubs.get(key);
        if (have != null) return have;
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "s" + stubs.size() + "_" + x.name, sd, null, null);
        miss(m, x.owner + "." + x.name);

        int it = -1, slot = 0;
        for (Type t : Type.getArgumentTypes(sd)) { if (it < 0 && t.getDescriptor().equals("Lnet/minecraft/core/item/Item;")) it = slot; slot += t.getSize(); }
        if (!stat && returns_self(x.owner, Type.getReturnType(sd))) {
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
        } else if (it >= 0 && Type.getReturnType(sd).getDescriptor().equals("Lnet/minecraft/core/item/ItemStack;")) {

            m.instructions.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/core/item/ItemStack"));
            m.instructions.add(new InsnNode(Opcodes.DUP));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, it));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/core/item/ItemStack", "<init>", "(Lnet/minecraft/core/item/IItemConvertible;)V", false));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
        } else if (singleton(Type.getReturnType(sd).getDescriptor()) != null) {

            Type r = Type.getReturnType(sd);
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, r.getInternalName(), singleton(r.getDescriptor()), "()" + r.getDescriptor(), false));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
        } else dflt(m, Type.getReturnType(sd));
        stubs.put(key, m);
        return m;
    }

    boolean returns_self(String owner, Type r) {
        if (r.getSort() != Type.OBJECT) return false;
        for (String o = owner; o != null; o = g7.containsKey(o) ? g7.get(o).superName : null) if (o.equals(r.getInternalName())) return !o.equals("java/lang/Object");
        return false;
    }

    AbstractInsnNode stub_field(FieldInsnNode x) {

        if (x.getOpcode() == Opcodes.GETSTATIC && x.desc.equals("Lnet/minecraft/core/item/Item;")) {
            stubbed++;
            MethodNode g = stubs.get("i " + x.owner + "." + x.name);
            if (g == null) {
                g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "i" + stubs.size() + "_" + x.name, "()Lnet/minecraft/core/item/Item;", null, null);
                g.instructions.add(new LdcInsnNode(x.owner.substring(x.owner.lastIndexOf('/') + 1) + "." + x.name));
                g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Missing", "item", "(Ljava/lang/String;)Lnet/minecraft/core/item/Item;", false));
                g.instructions.add(new InsnNode(Opcodes.ARETURN));
                stubs.put("i " + x.owner + "." + x.name, g);
            }
            return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
        }

        ClassNode k8 = shim.get("openhouse/v7/K8");
        if (k8 != null && x.getOpcode() == Opcodes.GETSTATIC) {
            String kn = x.owner.replace('/', '_') + "__" + x.name;
            for (MethodNode m : k8.methods) if (m.name.equals(kn) && m.desc.equals("()" + x.desc)) { rewired++; return new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/K8", kn, m.desc, false); }
        }

        if (x.getOpcode() == Opcodes.GETSTATIC && x.owner.equals("net/minecraft/core/block/Blocks") && x.desc.equals("Lnet/minecraft/core/block/Block;")) {
            stubbed++;
            MethodNode g = stubs.get("b " + x.name);
            if (g == null) {
                g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "b" + stubs.size() + "_" + x.name, "()Lnet/minecraft/core/block/Block;", null, null);
                g.instructions.add(new LdcInsnNode(x.name));
                g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Fill8", "block", "(Ljava/lang/String;)Lnet/minecraft/core/block/Block;", false));
                g.instructions.add(new InsnNode(Opcodes.ARETURN));
                stubs.put("b " + x.name, g);
            }
            return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
        }

        if (x.getOpcode() == Opcodes.GETSTATIC && x.desc.equals("Lnet/minecraft/core/data/tag/Tag;")) {
            stubbed++;
            MethodNode g = stubs.get("t " + x.owner + "." + x.name);
            if (g == null) {
                g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "t" + stubs.size() + "_" + x.name, "()Lnet/minecraft/core/data/tag/Tag;", null, null);
                g.instructions.add(new LdcInsnNode(x.owner.substring(x.owner.lastIndexOf('/') + 1) + "." + x.name));
                g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Missing", "tag", "(Ljava/lang/String;)Lnet/minecraft/core/data/tag/Tag;", false));
                g.instructions.add(new InsnNode(Opcodes.ARETURN));
                stubs.put("t " + x.owner + "." + x.name, g);
            }
            return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
        }

        String one = x.getOpcode() == Opcodes.GETSTATIC || x.getOpcode() == Opcodes.GETFIELD ? singleton(x.desc) : null;
        if (one != null) {
            boolean inst = x.getOpcode() == Opcodes.GETFIELD;
            String d = "(" + (inst ? "L" + x.owner + ";" : "") + ")" + x.desc, key = "1 " + x.owner + "." + x.name + d;
            MethodNode g = stubs.get(key);
            if (g == null) {
                g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "o" + stubs.size() + "_" + x.name, d, null, null);
                String t = Type.getType(x.desc).getInternalName();
                g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, t, one, "()" + x.desc, false));
                g.instructions.add(new InsnNode(Opcodes.ARETURN));
                g.maxStack = 1;
                g.maxLocals = inst ? 1 : 0;
                stubs.put(key, g);
            }
            rewired++;
            return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
        }
        String hold = "F" + Integer.toHexString((x.owner + "." + x.name + x.desc).hashCode() & 0x7fffffff);
        hold_fields.put(hold, x.desc);
        boolean get = x.getOpcode() == Opcodes.GETSTATIC || x.getOpcode() == Opcodes.GETFIELD;
        boolean inst = x.getOpcode() == Opcodes.GETFIELD || x.getOpcode() == Opcodes.PUTFIELD;
        String d = get ? "(" + (inst ? "L" + x.owner + ";" : "") + ")" + x.desc : "(" + (inst ? "L" + x.owner + ";" : "") + x.desc + ")V";
        String key = "f " + hold + d;
        MethodNode m = stubs.get(key);
        if (m == null) {
            m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, (get ? "g" : "p") + stubs.size() + "_" + x.name, d, null, null);
            miss(m, x.owner + "." + x.name);
            Type t = Type.getType(x.desc);
            if (get) {
                m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, gen_class, hold, x.desc));
                m.instructions.add(new InsnNode(t.getOpcode(Opcodes.IRETURN)));
            } else {
                m.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), inst ? 1 : 0));
                m.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, gen_class, hold, x.desc));
                m.instructions.add(new InsnNode(Opcodes.RETURN));
            }
            stubs.put(key, m);
        }
        stubbed++;
        return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, m.name, m.desc, false);
    }
    final Map<String, String> hold_fields = new TreeMap<>();

    static final Set<String> ONE_NAMES = new HashSet<>(Arrays.asList("getMinecraft", "getInstance", "instance", "getServer"));
    String singleton(String desc) {
        if (!desc.startsWith("L")) return null;
        ClassNode c = g7.get(desc.substring(1, desc.length() - 1));
        if (c == null) return null;
        for (MethodNode m : c.methods)
            if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) == (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC) && m.desc.equals("()" + desc) && ONE_NAMES.contains(m.name)) return m.name;
        return null;
    }

    static void miss(MethodNode m, String what) {
        m.instructions.add(new LdcInsnNode(what));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Miss", "once", "(Ljava/lang/String;)V", false));
    }
    static void dflt(MethodNode m, Type r) {

        if (r.getSort() == Type.OBJECT && STUBS.containsKey(r.getInternalName()) && !STUBS.get(r.getInternalName()).itf) {

            m.instructions.add(new TypeInsnNode(Opcodes.NEW, r.getInternalName()));
            m.instructions.add(new InsnNode(Opcodes.DUP));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, r.getInternalName(), "<init>", "()V", false));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
            return;
        }

        String coll = EMPTY_OF.get(r.getDescriptor());
        if (coll != null) {
            m.instructions.add(new TypeInsnNode(Opcodes.NEW, coll));
            m.instructions.add(new InsnNode(Opcodes.DUP));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, coll, "<init>", "()V", false));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
            return;
        }
        switch (r.getSort()) {
            case Type.VOID: m.instructions.add(new InsnNode(Opcodes.RETURN)); return;
            case Type.LONG: m.instructions.add(new InsnNode(Opcodes.LCONST_0)); break;
            case Type.FLOAT: m.instructions.add(new InsnNode(Opcodes.FCONST_0)); break;
            case Type.DOUBLE: m.instructions.add(new InsnNode(Opcodes.DCONST_0)); break;

            case Type.ARRAY: empty_array(m.instructions, r); break;
            case Type.OBJECT: m.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); break;
            default: m.instructions.add(new InsnNode(Opcodes.ICONST_0));
        }
        m.instructions.add(new InsnNode(r.getOpcode(Opcodes.IRETURN)));
    }

    static void empty_array(InsnList il, Type r) {
        il.add(new InsnNode(Opcodes.ICONST_0));
        String comp = r.getDescriptor().substring(1);
        if (r.getDimensions() == 1 && r.getElementType().getSort() < Type.ARRAY) {
            int t;
            switch (r.getElementType().getSort()) {
                case Type.BOOLEAN: t = Opcodes.T_BOOLEAN; break;
                case Type.CHAR: t = Opcodes.T_CHAR; break;
                case Type.BYTE: t = Opcodes.T_BYTE; break;
                case Type.SHORT: t = Opcodes.T_SHORT; break;
                case Type.INT: t = Opcodes.T_INT; break;
                case Type.FLOAT: t = Opcodes.T_FLOAT; break;
                case Type.LONG: t = Opcodes.T_LONG; break;
                default: t = Opcodes.T_DOUBLE;
            }
            il.add(new IntInsnNode(Opcodes.NEWARRAY, t));
        } else il.add(new TypeInsnNode(Opcodes.ANEWARRAY, comp.startsWith("L") ? comp.substring(1, comp.length() - 1) : comp));
    }

    static final Map<String, String> EMPTY_OF = new HashMap<>();
    static {
        for (String t : new String[] { "Ljava/util/List;", "Ljava/util/Collection;", "Ljava/lang/Iterable;", "Ljava/util/ArrayList;" }) EMPTY_OF.put(t, "java/util/ArrayList");
        for (String t : new String[] { "Ljava/util/Set;", "Ljava/util/HashSet;" }) EMPTY_OF.put(t, "java/util/HashSet");
        for (String t : new String[] { "Ljava/util/Map;", "Ljava/util/HashMap;" }) EMPTY_OF.put(t, "java/util/HashMap");

        EMPTY_OF.put("Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap");
        EMPTY_OF.put("Lit/unimi/dsi/fastutil/objects/Object2IntMap;", "it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap");
        EMPTY_OF.put("Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;", "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap");
        EMPTY_OF.put("Lit/unimi/dsi/fastutil/ints/IntList;", "it/unimi/dsi/fastutil/ints/IntArrayList");
        EMPTY_OF.put("Lit/unimi/dsi/fastutil/objects/ObjectList;", "it/unimi/dsi/fastutil/objects/ObjectArrayList");
    }

    static final Map<String, String> JDK_CALLS = new HashMap<>();
    static {
        JDK_CALLS.put("java/nio/file/FileSystems.newFileSystem(Ljava/net/URI;Ljava/util/Map;)Ljava/nio/file/FileSystem;", "openhouse/v7/Jdk");
        JDK_CALLS.put("java/nio/file/FileSystems.newFileSystem(Ljava/net/URI;Ljava/util/Map;Ljava/lang/ClassLoader;)Ljava/nio/file/FileSystem;", "openhouse/v7/Jdk");
    }

    static final Map<String, String[]> LIB_CALLS = new HashMap<>();
    static {
        String sbm = "org/useless/dragonfly/models/block/StaticBlockModel", sbm_d = "L" + sbm + ";";
        String att = "renderAttached(Lnet/minecraft/client/render/block/model/generic/BlockModelGeneric;Lnet/minecraft/client/render/tessellator/TessellatorGeneral;"
                + "Lnet/minecraft/core/world/WorldSource;Lnet/minecraft/core/world/pos/TilePosc;IIIDDDZZLnet/minecraft/client/render/texture/stitcher/IconCoordinate;)Z";
        String sta = "renderStandalone(Lnet/minecraft/client/render/block/model/generic/BlockModelGeneric;Lnet/minecraft/client/render/tessellator/TessellatorGeneral;"
                + "DDDIBLnet/minecraft/client/render/block/color/BlockColor;)Z";
        for (String o : new String[] { sbm, "org/useless/dragonfly/models/block/mojang/StaticBlockModelMojang" }) {
            LIB_CALLS.put(o + "." + att, new String[] { "openhouse/v7/JsonModel", "attached", sbm_d });
            LIB_CALLS.put(o + "." + sta, new String[] { "openhouse/v7/JsonModel", "standalone", sbm_d });
        }
    }

    void call(MethodNode in_m, MethodInsnNode x, String where) {
        if (x.owner.startsWith("[")) return;
        String jdk = x.getOpcode() == Opcodes.INVOKESTATIC ? JDK_CALLS.get(x.owner + "." + x.name + x.desc) : null;
        if (jdk != null) { x.owner = jdk; x.itf = false; rewired++; return; }

        String[] lc = x.getOpcode() != Opcodes.INVOKESTATIC ? LIB_CALLS.get(x.owner + "." + x.name + x.desc) : null;
        if (lc != null) {
            x.setOpcode(Opcodes.INVOKESTATIC); x.owner = lc[0]; x.name = lc[1]; x.desc = "(" + lc[2] + x.desc.substring(1); x.itf = false;
            rewired++;
            return;
        }

        if (x.getOpcode() == Opcodes.INVOKEVIRTUAL && WRAP_SWAP != null && WRAP_SWAP.containsValue(x.owner)) { x.setOpcode(Opcodes.INVOKEINTERFACE); x.itf = true; }

        if (x.name.equals("copyUnpooled") && x.desc.equals("()Lnet/minecraft/core/util/pool/Poolable;") && x.getOpcode() != Opcodes.INVOKESTATIC) {
            in_m.instructions.set(x, new InsnNode(Opcodes.NOP));
            rewired++;
            return;
        }

        if ((x.getOpcode() == Opcodes.INVOKEINTERFACE || x.getOpcode() == Opcodes.INVOKESTATIC && x.itf) && own.containsKey(x.owner)) {
            ClassNode oc = own.get(x.owner);
            MethodNode om = null;
            for (MethodNode m : oc.methods) if (m.name.equals(x.name) && m.desc.equals(x.desc)) om = m;
            if (DEAD_ACC.contains(x.owner + "." + x.name + x.desc) || om != null && is_mixin(oc) && accessor_dead(oc, om)) {
                MethodNode st = stub_call(x);
                x.setOpcode(Opcodes.INVOKESTATIC); x.owner = gen_class; x.name = st.name; x.desc = st.desc; x.itf = false;
                stubbed++;
                return;
            }
        }
        if (!game(x.owner)) {

            String go = own.containsKey(x.owner) && x.getOpcode() != Opcodes.INVOKESTATIC && !x.name.equals("<init>") ? game_owner(x.owner, x.name, x.desc, true) : null;
            if (go == null) return;

            if (!known_class(go)) {

                String p = go;
                for (int k = 0; k < 20 && p != null && !known_class(p); k++) p = parent(p);
                if (p == null || find(p, x.name, x.desc) == null) stub(go).methods.merge(x.name + x.desc, Opcodes.INVOKEVIRTUAL, (a, b) -> a);
                return;
            }
            MethodNode gm = find(go, x.name, x.desc);
            if (gm != null) { open_method(gm, found_in, true); return; }
            x.owner = go;
        }
        if (!known_class(x.owner)) {
            unresolved.merge("class " + x.owner, 1, Integer::sum);
            NEED.merge(x.owner + "." + x.name + x.desc, 1, Integer::sum);
            Stub st = stub(x.owner);
            if (x.getOpcode() == Opcodes.INVOKEINTERFACE) st.itf = true;
            st.methods.merge(x.name + x.desc, x.getOpcode(), (a, b) -> a);
            return;
        }
        MethodNode fm = find(x.owner, x.name, x.desc);
        if (fm != null) { open_method(fm, found_in, x.getOpcode() == Opcodes.INVOKESPECIAL && !x.name.equals("<init>")); return; }

        for (String anc : ancestors(x.owner)) {
            String sh = "openhouse/v7/S" + anc.substring(anc.lastIndexOf('/') + 1);
            ClassNode sc = shim.get(sh);
            if (sc == null) continue;
            String sd = x.getOpcode() == Opcodes.INVOKESTATIC ? x.desc : "(L" + anc + ";" + x.desc.substring(1);
            for (MethodNode m : sc.methods) if (m.name.equals(x.name) && m.desc.equals(sd)) {
                x.setOpcode(Opcodes.INVOKESTATIC); x.owner = sh; x.desc = sd; x.itf = false; rewired++;
                return;
            }

            String er = erase(sd);
            if (!er.equals(sd)) for (MethodNode m : sc.methods) if (m.name.equals(x.name) && m.desc.equals(er)) {
                Type want = Type.getReturnType(sd);
                x.setOpcode(Opcodes.INVOKESTATIC); x.owner = sh; x.desc = er; x.itf = false; rewired++;
                if (!Type.getReturnType(er).equals(want) && want.getSort() == Type.OBJECT) in_m.instructions.insert(x, new TypeInsnNode(Opcodes.CHECKCAST, want.getInternalName()));
                return;
            }
        }

        if (!x.name.equals("<init>")) {
            String args = x.desc.substring(0, x.desc.indexOf(')') + 1);
            boolean stat = x.getOpcode() == Opcodes.INVOKESTATIC;
            for (String anc : ancestors(x.owner)) {
                ClassNode c = g7.get(anc);
                if (c == null) continue;
                for (MethodNode m : c.methods) {
                    if (!m.name.equals(x.name) || !m.desc.startsWith(args) || m.desc.equals(x.desc) || ((m.access & Opcodes.ACC_STATIC) != 0) != stat) continue;

                    if (Type.getReturnType(x.desc).getSort() == Type.VOID && find(x.owner, x.name, m.desc) != null) {
                        open_method(find(x.owner, x.name, m.desc), found_in, x.getOpcode() == Opcodes.INVOKESPECIAL);
                        x.desc = m.desc;
                        in_m.instructions.insert(x, new InsnNode(Type.getReturnType(m.desc).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                        rewired++;
                        return;
                    }
                    String[] cv = converter(Type.getReturnType(m.desc).getDescriptor(), Type.getReturnType(x.desc).getDescriptor());
                    if (cv == null) continue;
                    MethodNode fm2 = find(x.owner, x.name, m.desc);
                    if (fm2 == null) continue;
                    open_method(fm2, found_in, x.getOpcode() == Opcodes.INVOKESPECIAL);
                    x.desc = m.desc;
                    in_m.instructions.insert(x, new MethodInsnNode(Opcodes.INVOKESTATIC, cv[0], cv[1], cv[2], false));
                    rewired++;
                    return;
                }
            }
        }

        String rn = RENAMES.get(x.name);
        if (rn != null && !rn.equals(x.name) && !x.name.equals("<init>")) {
            MethodNode t = find(x.owner, rn, x.desc);
            if (t != null && t != OBJ_ANY && ((t.access & Opcodes.ACC_STATIC) != 0) == (x.getOpcode() == Opcodes.INVOKESTATIC)) {
                open_method(t, found_in, x.getOpcode() == Opcodes.INVOKESPECIAL);
                x.name = rn;
                rewired++;
                return;
            }
        }
        String d7x = x.desc.replace(POSC, "III").replace(POS, "III");

        Type[] a8 = Type.getArgumentTypes(x.desc);
        String cut = a8.length > 0 && a8[a8.length - 1].getSort() == Type.INT ? minus_last_int(d7x) : null;

        String ids = block_ids(d7x), ids_cut = ids != null && cut != null ? block_ids(cut) : null;
        for (String d7 : new String[] { d7x.equals(x.desc) ? null : d7x, cut, ids, ids_cut }) if (d7 != null && !x.name.equals("<init>")) {
            for (String n7 : new String[] { x.name, RENAMES.get(x.name) }) {
                if (n7 == null) continue;
                MethodNode t = find(x.owner, n7, d7);
                if (t == null || t == OBJ_ANY || ((t.access & Opcodes.ACC_STATIC) != 0) != (x.getOpcode() == Opcodes.INVOKESTATIC)) continue;

                if (x.getOpcode() == Opcodes.INVOKESPECIAL) { unpack_inline(in_m, x, n7, d7); adapted++; return; }
                MethodNode a = adapter(x, n7, d7);
                x.setOpcode(Opcodes.INVOKESTATIC);
                x.owner = gen_class;
                x.name = a.name;
                x.desc = a.desc;
                x.itf = false;
                adapted++;
                return;
            }
        }
        if (!x.name.equals("<init>") && retyped_args(in_m, x)) return;

        if (!x.name.equals("<init>") && a8.length > 0) {
            String ret = Type.getReturnType(x.desc).getDescriptor();
            Type last = a8[a8.length - 1];
            if ((last.getSort() == Type.OBJECT || last.getSort() == Type.ARRAY) && !last.getDescriptor().equals(POS) && !last.getDescriptor().equals(POSC)) {
                StringBuilder d = new StringBuilder("(");
                for (int i = 0; i < a8.length - 1; i++) d.append(a8[i].getDescriptor());
                String d7 = d.append(')').append(ret).toString();
                MethodNode t = find(x.owner, x.name, d7);
                if (t != null && t != OBJ_ANY && ((t.access & Opcodes.ACC_STATIC) != 0) == (x.getOpcode() == Opcodes.INVOKESTATIC)) {
                    open_method(t, found_in, x.getOpcode() == Opcodes.INVOKESPECIAL);
                    in_m.instructions.insertBefore(x, new InsnNode(Opcodes.POP));
                    x.desc = d7;
                    rewired++;
                    return;
                }
            }
            if (a8.length == 2 && a8[0].getSort() >= Type.ARRAY && a8[1].getSort() >= Type.ARRAY && !a8[0].equals(a8[1])) {
                String d7 = "(" + a8[1].getDescriptor() + a8[0].getDescriptor() + ")" + ret;
                MethodNode t = find(x.owner, x.name, d7);
                if (t != null && t != OBJ_ANY && ((t.access & Opcodes.ACC_STATIC) != 0) == (x.getOpcode() == Opcodes.INVOKESTATIC)) {
                    open_method(t, found_in, x.getOpcode() == Opcodes.INVOKESPECIAL);
                    in_m.instructions.insertBefore(x, new InsnNode(Opcodes.SWAP));
                    x.desc = d7;
                    rewired++;
                    return;
                }
            }
        }

        if (x.getOpcode() != Opcodes.INVOKESTATIC && x.desc.startsWith("()") && !x.name.equals("<init>"))
            for (String o = x.owner; o != null && g7.containsKey(o); o = g7.get(o).superName)
                for (FieldNode f : g7.get(o).fields)
                    if (f.name.equals(x.name) && f.desc.equals(Type.getReturnType(x.desc).getDescriptor()) && (f.access & Opcodes.ACC_STATIC) == 0) {
                        open_field(f, o, false);
                        in_m.instructions.set(x, new FieldInsnNode(Opcodes.GETFIELD, o, f.name, f.desc));
                        rewired++;
                        return;
                    }
        unresolved.merge("method " + x.owner + "." + x.name + x.desc, 1, Integer::sum);
        if (x.name.equals("<init>")) { ctor(in_m, x); return; }
        MethodNode st = stub_call(x);
        x.setOpcode(Opcodes.INVOKESTATIC); x.owner = gen_class; x.name = st.name; x.desc = st.desc; x.itf = false;
        stubbed++;
    }

    static final String AABB8 = "Lorg/joml/primitives/AABBdc;", AABB7 = "Lnet/minecraft/core/util/phys/AABB;";
    boolean retyped_args(MethodNode in_m, MethodInsnNode x) {
        Type[] a8 = Type.getArgumentTypes(x.desc);
        boolean any = false;
        for (Type t : a8) { String d = t.getDescriptor(); any |= d.equals(TESS8) || d.equals(AABB8); }
        if (!any) return false;

        for (boolean no_t : new boolean[] { false, true }) for (String pos : new String[] { "III", "DDD" }) for (boolean ids : new boolean[] { false, true }) {
            StringBuilder b = new StringBuilder("(");
            for (Type t : a8) {
                String d = t.getDescriptor();
                b.append(d.equals(TESS8) ? (no_t ? "" : TESS7) : d.equals(AABB8) ? AABB7 : d.equals(POS) || d.equals(POSC) ? pos : ids && d.equals(BLOCK) ? "I" : d);
            }
            String d7 = b.append(')').append(Type.getReturnType(x.desc).getDescriptor()).toString();
            for (String n7 : new String[] { x.name, RENAMES.get(x.name) }) {
                if (n7 == null) continue;
                MethodNode t = find(x.owner, n7, d7);
                if (t == null || t == OBJ_ANY || ((t.access & Opcodes.ACC_STATIC) != 0) != (x.getOpcode() == Opcodes.INVOKESTATIC)) continue;
                open_method(t, found_in, x.getOpcode() == Opcodes.INVOKESPECIAL);
                int base = in_m.maxLocals, size = 0;
                int[] at = new int[a8.length];
                for (int i = 0; i < a8.length; i++) { at[i] = base + size; size += a8[i].getSize(); }
                InsnList il = new InsnList();
                for (int i = a8.length - 1; i >= 0; i--) il.add(new VarInsnNode(a8[i].getOpcode(Opcodes.ISTORE), at[i]));

                for (Type t8 : a8) if (t8.getDescriptor().equals(TESS8)) { il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/render/renderer/GLRenderer", "flush", "()V", false)); break; }
                for (int i = 0; i < a8.length; i++) {
                    String d = a8[i].getDescriptor();
                    if (d.equals(TESS8)) { if (!no_t) il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/client/render/tessellator/Tessellator", "instance", TESS7)); }
                    else if (d.equals(AABB8)) {
                        il.add(new VarInsnNode(Opcodes.ALOAD, at[i]));
                        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Box", "to7", "(" + AABB8 + ")" + AABB7, false));
                    } else if (d.equals(POS) || d.equals(POSC)) {
                        for (String c : new String[] { "x", "y", "z" }) {
                            il.add(new VarInsnNode(Opcodes.ALOAD, at[i]));
                            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/core/world/pos/TilePosc", c, "()I", true));
                            if (pos.equals("DDD")) il.add(new InsnNode(Opcodes.I2D));
                        }
                    } else if (ids && d.equals(BLOCK)) {
                        il.add(new VarInsnNode(Opcodes.ALOAD, at[i]));
                        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/SBlock", "id_of", "(" + BLOCK + ")I", false));
                    } else il.add(new VarInsnNode(a8[i].getOpcode(Opcodes.ILOAD), at[i]));
                }
                in_m.instructions.insertBefore(x, il);
                in_m.maxLocals += size;
                in_m.maxStack += 8;
                x.name = n7;
                x.desc = d7;
                rewired++;
                return true;
            }
        }
        return false;
    }

    String erase(String desc) {
        StringBuilder b = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("L([^;]+);").matcher(desc);
        int at = 0;
        while (m.find()) {
            b.append(desc, at, m.start());
            String n = m.group(1);
            b.append(game(n) && !known_class(n) ? "Ljava/lang/Object;" : m.group());
            at = m.end();
        }
        return b.append(desc.substring(at)).toString();
    }

    void ctor(MethodNode in_m, MethodInsnNode x) {
        if (x.getOpcode() != Opcodes.INVOKESPECIAL) return;

        String fr = "Lnet/minecraft/client/render/font/FontRenderer;", ft = "Lnet/minecraft/client/render/Font;";
        if (x.desc.contains(fr) && find(x.owner, "<init>", x.desc.replace(fr, ft)) != null) {
            swap_args(in_m, x, x.desc.replace(fr, ft), fr);
            rewired++;
            return;
        }
        String pre = "(Lnet/minecraft/client/option/GameSettings;" + x.desc.substring(1);
        if (find(x.owner, "<init>", pre) != null) {
            Type[] args = Type.getArgumentTypes(x.desc);
            int base = in_m.maxLocals, size = 0;
            int[] at = new int[args.length];
            for (int i = 0; i < args.length; i++) { at[i] = base + size; size += args[i].getSize(); }
            InsnList il = new InsnList();
            for (int i = args.length - 1; i >= 0; i--) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), at[i]));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getMinecraft", "()Lnet/minecraft/client/Minecraft;", false));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "gameSettings", "Lnet/minecraft/client/option/GameSettings;"));
            for (int i = 0; i < args.length; i++) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), at[i]));
            in_m.instructions.insertBefore(x, il);
            in_m.maxLocals += size;
            in_m.maxStack += 2;
            x.desc = pre;
            rewired++;
            return;
        }
        String ret = "(" + x.desc.substring(1, x.desc.indexOf(')')) + ")L" + x.owner + ";";
        String fowner = null, fname = null;
        for (String anc : Collections.singletonList(x.owner)) {
            ClassNode sc = shim.get("openhouse/v7/S" + anc.substring(anc.lastIndexOf('/') + 1));
            if (sc != null) for (MethodNode m : sc.methods) if (m.name.equals("new_") && m.desc.equals(ret)) { fowner = sc.name; fname = m.name; }
        }
        if (fowner == null) {
            if (super_init(in_m, x)) return;
            MethodNode f = ctor_factory(x, ret);
            if (f == null) { if (!id_first(in_m, x) && !convert_args(in_m, x) && !plus_one(in_m, x)) fuzzy_ctor(in_m, x); return; }
            fowner = gen_class; fname = f.name;
        }
        AbstractInsnNode[] nd = new_dup(in_m, x);
        if (nd == null) { if (System.getProperty("vctor") != null) System.out.println("ctor: no NEW/DUP for " + x.owner + x.desc + " in " + in_m.name + " (" + why + ")"); return; }
        in_m.instructions.set(nd[0], new InsnNode(Opcodes.NOP));
        in_m.instructions.set(nd[1], new InsnNode(Opcodes.NOP));
        x.setOpcode(Opcodes.INVOKESTATIC); x.owner = fowner; x.name = fname; x.desc = ret; x.itf = false;
        rewired++;
        unresolved.remove("method " + "<init>");
    }

    void swap_args(MethodNode in_m, MethodInsnNode x, String desc7, String fr) {
        Type[] args = Type.getArgumentTypes(x.desc);
        int base = in_m.maxLocals, size = 0;
        int[] at = new int[args.length];
        for (int i = 0; i < args.length; i++) { at[i] = base + size; size += args[i].getSize(); }
        InsnList il = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), at[i]));
        for (int i = 0; i < args.length; i++) {
            if (args[i].getDescriptor().equals(fr)) {
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getMinecraft", "()Lnet/minecraft/client/Minecraft;", false));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "font", "Lnet/minecraft/client/render/Font;"));
            } else il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), at[i]));
        }
        in_m.instructions.insertBefore(x, il);
        in_m.maxLocals += size;
        in_m.maxStack += 2;
        x.desc = desc7;
    }

    Object[] string_less(MethodInsnNode x) {
        Type[] want = Type.getArgumentTypes(x.desc);
        ClassNode c = g7.get(x.owner);
        if (c == null) return null;
        for (MethodNode m : c.methods) {
            if (!m.name.equals("<init>") || (m.access & Opcodes.ACC_PRIVATE) != 0) continue;
            Type[] have = Type.getArgumentTypes(m.desc);
            if (have.length != want.length - 1) continue;
            for (int skip = 0; skip < want.length; skip++) {

                boolean only8 = want[skip].getSort() == Type.OBJECT && game(want[skip].getInternalName()) && !known_class(want[skip].getInternalName());
                if (!want[skip].getDescriptor().equals("Ljava/lang/String;") && !only8) continue;
                boolean ok = true;
                for (int i = 0, j = 0; i < want.length && ok; i++) { if (i == skip) continue; ok = want[i].equals(have[j++]); }
                if (ok) return new Object[] { m, skip };
            }
        }
        return null;
    }

    MethodNode key_setter(String owner) {
        for (String anc : ancestors(owner)) {
            ClassNode k = g7.get(anc);
            if (k != null) for (MethodNode m : k.methods)
                if ((m.name.equals("setKey") || m.name.equals("setLanguageKey")) && m.desc.startsWith("(Ljava/lang/String;)") && (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC) { found_in = anc; return m; }
        }
        return null;
    }

    boolean super_init(MethodNode in_m, MethodInsnNode x) {
        if (!in_m.name.equals("<init>") || cur_class == null || !own.containsKey(cur_class)) return false;
        String sup = own.get(cur_class).superName;
        if (sup == null || !moved(sup).equals(x.owner)) return false;
        Object[] sl = string_less(x);
        if (sl == null) return false;
        MethodNode m7 = (MethodNode) sl[0];
        int skip = (Integer) sl[1];
        Type[] args = Type.getArgumentTypes(x.desc);
        int base = in_m.maxLocals, size = 0;
        int[] at = new int[args.length];
        for (int i = 0; i < args.length; i++) { at[i] = base + size; size += args[i].getSize(); }
        InsnList il = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), at[i]));
        for (int i = 0; i < args.length; i++) if (i != skip) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), at[i]));
        in_m.instructions.insertBefore(x, il);
        x.desc = m7.desc;
        open_method(m7, x.owner, true);
        MethodNode s = args[skip].getDescriptor().equals("Ljava/lang/String;") ? key_setter(x.owner) : null;
        if (s != null) {
            InsnList after = new InsnList();
            after.add(new VarInsnNode(Opcodes.ALOAD, 0));
            after.add(new VarInsnNode(Opcodes.ALOAD, at[skip]));
            after.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, x.owner, s.name, s.desc, false));
            if (Type.getReturnType(s.desc).getSort() != Type.VOID) after.add(new InsnNode(Opcodes.POP));
            in_m.instructions.insert(x, after);
        }
        in_m.maxLocals += size;
        in_m.maxStack += 2;
        rewired++;
        return true;
    }

    static final String ID8 = "(Lnet/minecraft/core/util/collection/NamespaceID;Ljava/lang/String;", KEYID7 = "(Ljava/lang/String;Ljava/lang/String;";
    boolean id_first(MethodNode in_m, MethodInsnNode x) {
        if (!x.desc.startsWith(ID8)) return false;
        String d7 = KEYID7 + x.desc.substring(ID8.length());
        if (find(x.owner, "<init>", d7) == null) return false;
        Type[] args = Type.getArgumentTypes(x.desc);
        int base = in_m.maxLocals, size = 0;
        int[] at = new int[args.length];
        for (int i = 0; i < args.length; i++) { at[i] = base + size; size += args[i].getSize(); }
        InsnList il = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), at[i]));
        il.add(new VarInsnNode(Opcodes.ALOAD, at[1]));
        il.add(new VarInsnNode(Opcodes.ALOAD, at[0]));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/core/util/collection/NamespaceID", "toString", "()Ljava/lang/String;", false));
        for (int i = 2; i < args.length; i++) il.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), at[i]));
        in_m.instructions.insertBefore(x, il);
        in_m.maxLocals += size;
        in_m.maxStack += 2;
        x.desc = d7;
        open_method(find(x.owner, "<init>", d7), x.owner, false);
        rewired++;
        return true;
    }

    boolean fuzzy_ctor(MethodNode in_m, MethodInsnNode x) {
        ClassNode c = g7.get(x.owner);
        if (c == null) return false;
        Type[] want = Type.getArgumentTypes(x.desc);
        MethodNode best = null;
        int best_cost = 4;
        int[] best_from = null;
        for (MethodNode m : c.methods) {
            if (!m.name.equals("<init>") || (m.access & Opcodes.ACC_PRIVATE) != 0) continue;
            Type[] have = Type.getArgumentTypes(m.desc);
            int w = want.length, h = have.length;
            int[][] cost = new int[w + 1][h + 1];
            for (int[] r : cost) Arrays.fill(r, 99);
            cost[0][0] = 0;
            for (int i = 0; i <= w; i++) for (int j = 0; j <= h; j++) {
                if (cost[i][j] >= 99) continue;
                if (i < w && j < h) {
                    int k = want[i].equals(have[j]) ? 0 : want[i].getSort() == Type.ARRAY && want[i].getElementType().equals(have[j]) && want[i].getDimensions() == 1 ? 1 : 2;
                    cost[i + 1][j + 1] = Math.min(cost[i + 1][j + 1], cost[i][j] + k);
                }
                if (i < w && droppable(want, i)) cost[i + 1][j] = Math.min(cost[i + 1][j], cost[i][j] + 1);
                if (j < h) cost[i][j + 1] = Math.min(cost[i][j + 1], cost[i][j] + 1);
            }
            if (cost[w][h] >= best_cost) continue;

            int[] from = new int[h];
            Arrays.fill(from, -1);
            for (int i = w, j = h; i > 0 || j > 0; ) {
                if (i > 0 && j > 0) {
                    int k = want[i - 1].equals(have[j - 1]) ? 0 : want[i - 1].getSort() == Type.ARRAY && want[i - 1].getElementType().equals(have[j - 1]) && want[i - 1].getDimensions() == 1 ? 1 : 2;
                    if (cost[i][j] == cost[i - 1][j - 1] + k) { if (k < 2) from[j - 1] = i - 1; i--; j--; continue; }
                }
                if (i > 0 && droppable(want, i - 1) && cost[i][j] == cost[i - 1][j] + 1) { i--; continue; }
                j--;
            }
            best = m; best_cost = cost[w][h]; best_from = from;
        }
        if (best == null) return false;
        int recv = ctor_receiver(in_m, x);
        Type[] have = Type.getArgumentTypes(best.desc);
        int base = in_m.maxLocals, size = 0;
        int[] at = new int[want.length];
        for (int i = 0; i < want.length; i++) { at[i] = base + size; size += want[i].getSize(); }
        InsnList il = new InsnList();
        for (int i = want.length - 1; i >= 0; i--) il.add(new VarInsnNode(want[i].getOpcode(Opcodes.ISTORE), at[i]));
        for (int j = 0; j < have.length; j++) {
            int i = best_from[j];
            if (i < 0) { il.add(fill(have[j])); continue; }
            il.add(new VarInsnNode(want[i].getOpcode(Opcodes.ILOAD), at[i]));
            if (!want[i].equals(have[j])) {

                LabelNode none = new LabelNode(), done = new LabelNode();
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new JumpInsnNode(Opcodes.IFNULL, none));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new InsnNode(Opcodes.ARRAYLENGTH));
                il.add(new JumpInsnNode(Opcodes.IFEQ, none));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(have[j].getOpcode(Opcodes.IALOAD)));
                il.add(new JumpInsnNode(Opcodes.GOTO, done));
                il.add(none);
                il.add(new InsnNode(Opcodes.POP));
                il.add(dflt_value(have[j]));
                il.add(done);
                frames_needed = true;
            }
        }
        in_m.instructions.insertBefore(x, il);
        in_m.maxLocals += size;
        in_m.maxStack += 6;
        boolean[] dropped = new boolean[want.length];
        Arrays.fill(dropped, true);
        for (int i : best_from) if (i >= 0) dropped[i] = false;
        ctor_hooks(in_m, x, want, at, dropped, recv);
        x.desc = best.desc;
        open_method(best, x.owner, false);
        rewired++;
        return true;
    }
    boolean frames_needed;

    static final int RECV_NONE = 0, RECV_THIS = 1, RECV_STACK = 2;
    int ctor_receiver(MethodNode in_m, MethodInsnNode x) {
        if (x.getOpcode() != Opcodes.INVOKESPECIAL) return RECV_NONE;
        if (new_dup(in_m, x) != null) return RECV_STACK;
        if (in_m.name.equals("<init>") && cur_class != null && own.containsKey(cur_class) && own.get(cur_class).superName != null && moved(own.get(cur_class).superName).equals(x.owner)) return RECV_THIS;
        return RECV_NONE;
    }

    boolean has_hook(String t) {
        ClassNode k = shim.get(t);
        if (k == null || g7.containsKey(t)) return false;
        for (MethodNode m : k.methods) if (m.name.equals("apply") && (m.access & Opcodes.ACC_STATIC) != 0 && m.desc.endsWith("L" + t + ";)V")) return true;
        return false;
    }

    MethodNode hook_for(String t, String built) {
        ClassNode k = shim.get(t);
        if (k == null || g7.containsKey(t)) return null;
        for (String a : ancestors(built))
            for (MethodNode m : k.methods)
                if (m.name.equals("apply") && (m.access & Opcodes.ACC_STATIC) != 0 && m.desc.equals("(L" + a + ";L" + t + ";)V")) return m;
        return null;
    }

    void ctor_hooks(MethodNode in_m, MethodInsnNode x, Type[] want, int[] at, boolean[] dropped, int recv) {
        if (recv == RECV_NONE) return;
        InsnList after = new InsnList();
        boolean key_done = false;
        for (int i = 0; i < want.length; i++) {
            if (!dropped[i]) continue;
            if (want[i].getDescriptor().equals("Ljava/lang/String;")) {
                if (key_done) continue;
                MethodNode s = key_setter(x.owner);
                if (s == null) continue;
                key_done = true;
                after.add(recv == RECV_THIS ? new VarInsnNode(Opcodes.ALOAD, 0) : new InsnNode(Opcodes.DUP));
                after.add(new VarInsnNode(Opcodes.ALOAD, at[i]));
                after.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, x.owner, s.name, s.desc, false));
                if (Type.getReturnType(s.desc).getSort() != Type.VOID) after.add(new InsnNode(Opcodes.POP));
                continue;
            }
            if (want[i].getSort() != Type.OBJECT) continue;
            MethodNode h = hook_for(want[i].getInternalName(), x.owner);
            if (h == null) continue;
            after.add(recv == RECV_THIS ? new VarInsnNode(Opcodes.ALOAD, 0) : new InsnNode(Opcodes.DUP));
            after.add(new VarInsnNode(Opcodes.ALOAD, at[i]));
            after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, want[i].getInternalName(), h.name, h.desc, false));
        }
        if (after.size() > 0) { in_m.instructions.insert(x, after); in_m.maxStack += 2; }
    }

    boolean droppable(Type[] want, int i) {
        if (droppable(want[i])) return true;
        for (int k = i; k < want.length; k++) if (want[k].getSort() > Type.DOUBLE) return false;
        return true;
    }

    boolean droppable(Type t) {
        return t.getDescriptor().equals("Ljava/lang/String;") || t.getSort() == Type.OBJECT && game(t.getInternalName()) && (!known_class(t.getInternalName()) || has_hook(t.getInternalName()));
    }

    AbstractInsnNode fill(Type t) {
        ClassNode k = t.getSort() == Type.OBJECT ? g7.get(t.getInternalName()) : null;
        if (k != null && (k.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0)
            for (MethodNode km : k.methods) if (km.name.equals("<init>") && km.desc.equals("()V") && (km.access & Opcodes.ACC_PUBLIC) != 0) return fresh(k.name);
        return dflt_value(t);
    }

    AbstractInsnNode fresh(String k) {
        String key = "n " + k;
        MethodNode g = stubs.get(key);
        if (g == null) {
            g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "n" + stubs.size() + "_new", "()L" + k + ";", null, null);
            g.instructions.add(new TypeInsnNode(Opcodes.NEW, k));
            g.instructions.add(new InsnNode(Opcodes.DUP));
            g.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, k, "<init>", "()V", false));
            g.instructions.add(new InsnNode(Opcodes.ARETURN));
            stubs.put(key, g);
        }
        return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
    }

    boolean plus_one(MethodNode in_m, MethodInsnNode x) {
        ClassNode c = g7.get(x.owner);
        if (c == null) return false;
        Type[] want = Type.getArgumentTypes(x.desc);
        for (MethodNode m : c.methods) {
            if (!m.name.equals("<init>") || (m.access & Opcodes.ACC_PRIVATE) != 0) continue;
            Type[] have = Type.getArgumentTypes(m.desc);
            if (have.length != want.length + 1) continue;
            int extra = -1;
            for (int e = 0; e < have.length && extra < 0; e++) {
                boolean ok = true;
                for (int i = 0, j = 0; i < have.length && ok; i++) { if (i == e) continue; ok = have[i].equals(want[j++]); }
                if (ok) extra = e;
            }
            if (extra < 0) continue;
            int base = in_m.maxLocals, size = 0;
            int[] at = new int[want.length];
            for (int i = 0; i < want.length; i++) { at[i] = base + size; size += want[i].getSize(); }
            InsnList il = new InsnList();
            for (int i = want.length - 1; i >= 0; i--) il.add(new VarInsnNode(want[i].getOpcode(Opcodes.ISTORE), at[i]));
            for (int i = 0, j = 0; i < have.length; i++) {
                if (i != extra) { il.add(new VarInsnNode(want[j].getOpcode(Opcodes.ILOAD), at[j])); j++; continue; }
                Type t = have[i];
                ClassNode k = t.getSort() == Type.OBJECT ? g7.get(t.getInternalName()) : null;
                boolean fresh = false;
                if (k != null && (k.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0)
                    for (MethodNode km : k.methods) if (km.name.equals("<init>") && km.desc.equals("()V") && (km.access & Opcodes.ACC_PUBLIC) != 0) fresh = true;
                if (fresh) {
                    il.add(new TypeInsnNode(Opcodes.NEW, k.name));
                    il.add(new InsnNode(Opcodes.DUP));
                    il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, k.name, "<init>", "()V", false));
                } else il.add(dflt_value(t));
            }
            in_m.instructions.insertBefore(x, il);
            in_m.maxLocals += size;
            in_m.maxStack += 4;
            x.desc = m.desc;
            open_method(m, x.owner, false);
            rewired++;
            return true;
        }
        return false;
    }

    boolean convert_args(MethodNode in_m, MethodInsnNode x) {
        ClassNode c = g7.get(x.owner);
        if (c == null) return false;
        Type[] want = Type.getArgumentTypes(x.desc);
        for (MethodNode m : c.methods) {
            if (!m.name.equals("<init>") || (m.access & Opcodes.ACC_PRIVATE) != 0) continue;
            Type[] have = Type.getArgumentTypes(m.desc);
            if (have.length != want.length) continue;
            String[][] cv = new String[want.length][];
            boolean ok = true, any = false;
            for (int i = 0; i < want.length && ok; i++) {
                if (want[i].equals(have[i])) continue;
                cv[i] = converter(want[i].getDescriptor(), have[i].getDescriptor());
                int same = 0;
                for (Type h : have) if (h.equals(have[i])) same++;

                ok = cv[i] != null && (same == 1 || cv[i][1].startsWith("from_"));
                any = true;
            }
            if (!ok || !any) continue;
            int base = in_m.maxLocals, size = 0;
            int[] at = new int[want.length];
            for (int i = 0; i < want.length; i++) { at[i] = base + size; size += want[i].getSize(); }
            InsnList il = new InsnList();
            for (int i = want.length - 1; i >= 0; i--) il.add(new VarInsnNode(want[i].getOpcode(Opcodes.ISTORE), at[i]));
            for (int i = 0; i < want.length; i++) {
                il.add(new VarInsnNode(want[i].getOpcode(Opcodes.ILOAD), at[i]));
                if (cv[i] != null) il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cv[i][0], cv[i][1], cv[i][2], false));
            }
            in_m.instructions.insertBefore(x, il);
            in_m.maxLocals += size;
            in_m.maxStack += 2;
            x.desc = m.desc;
            open_method(m, x.owner, false);
            rewired++;
            return true;
        }
        return false;
    }

    MethodNode ctor_factory(MethodInsnNode x, String ret) {
        Object[] sl = string_less(x);
        if (sl == null) return null;
        MethodNode m = (MethodNode) sl[0];
        int skip = (Integer) sl[1];
        Type[] want = Type.getArgumentTypes(x.desc);
        String key = "c " + x.owner + x.desc;
        MethodNode have_f = stubs.get(key);
        if (have_f != null) return have_f;
        MethodNode f = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "c" + stubs.size() + "_new", ret, null, null);
        f.instructions.add(new TypeInsnNode(Opcodes.NEW, x.owner));
        f.instructions.add(new InsnNode(Opcodes.DUP));
        int slot = 0, key_slot = 0;
        for (int i = 0; i < want.length; i++) {
            if (i != skip) f.instructions.add(new VarInsnNode(want[i].getOpcode(Opcodes.ILOAD), slot));
            else key_slot = slot;
            slot += want[i].getSize();
        }
        f.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, x.owner, "<init>", m.desc, false));
        if ((m.access & Opcodes.ACC_PUBLIC) == 0) widen.add("accessible method " + x.owner + " <init> " + m.desc);
        MethodNode s = want[skip].getDescriptor().equals("Ljava/lang/String;") ? key_setter(x.owner) : null;
        if (s != null) {
            f.instructions.add(new InsnNode(Opcodes.DUP));
            f.instructions.add(new VarInsnNode(Opcodes.ALOAD, key_slot));
            f.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, x.owner, s.name, s.desc, false));
            if (Type.getReturnType(s.desc).getSort() != Type.VOID) f.instructions.add(new InsnNode(Opcodes.POP));
        }
        f.instructions.add(new InsnNode(Opcodes.ARETURN));
        stubs.put(key, f);
        return f;
    }

    AbstractInsnNode[] new_dup(MethodNode m, MethodInsnNode x) {
        try {
            org.objectweb.asm.tree.analysis.Analyzer<org.objectweb.asm.tree.analysis.SourceValue> an =
                    new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter());
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] fr = an.analyze("x", m);
            int at = m.instructions.indexOf(x);
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue> f = fr[at];
            if (f == null) return null;
            int nargs = Type.getArgumentTypes(x.desc).length;
            org.objectweb.asm.tree.analysis.SourceValue recv = f.getStack(f.getStackSize() - nargs - 1);
            if (recv.insns.size() != 1) return null;
            AbstractInsnNode dup = recv.insns.iterator().next();
            if (dup.getOpcode() != Opcodes.DUP) return null;
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue> fd = fr[m.instructions.indexOf(dup)];
            org.objectweb.asm.tree.analysis.SourceValue made = fd.getStack(fd.getStackSize() - 1);
            if (made.insns.size() != 1) return null;
            AbstractInsnNode nw = made.insns.iterator().next();
            if (nw.getOpcode() != Opcodes.NEW) return null;
            for (AbstractInsnNode i = nw; i != null && i != x; i = i.getNext()) if (i instanceof FrameNode) return null;
            return new AbstractInsnNode[] { nw, dup };
        } catch (Exception e) {
            why = e.toString();
            return null;
        }
    }
    String why = "";

    AbstractInsnNode field(FieldInsnNode x, String where) {
        if (!game(x.owner)) {
            String go = own.containsKey(x.owner) ? game_owner(x.owner, x.name, x.desc, false) : null;
            if (go == null || !known_class(go)) return null;
            if (has_field(go, x.name, x.desc)) {

                FieldNode fn = field_node(go, x.name, x.desc);
                if (fn != null && (fn.access & Opcodes.ACC_PROTECTED) == 0) open_field(fn, field_in, x.getOpcode() == Opcodes.PUTFIELD || x.getOpcode() == Opcodes.PUTSTATIC);
                return null;
            }
            x.owner = go;
        }
        if (!known_class(x.owner)) {
            AbstractInsnNode cs = constant(x);
            if (cs != null) return cs;
            unresolved.merge("class " + x.owner, 1, Integer::sum);
            NEED.merge(x.owner + "." + x.name + " " + x.desc, 1, Integer::sum);
            stub(x.owner).fields.put(x.name + " " + x.desc, x.getOpcode() == Opcodes.GETSTATIC || x.getOpcode() == Opcodes.PUTSTATIC);
            return null;
        }
        if (has_field(x.owner, x.name, x.desc)) {
            FieldNode fn = field_node(x.owner, x.name, x.desc);
            boolean put = x.getOpcode() == Opcodes.PUTFIELD || x.getOpcode() == Opcodes.PUTSTATIC;
            if (fn != null) open_field(fn, field_in, put);
            if (fn == null || (fn.access & Opcodes.ACC_PROTECTED) == 0 || cur_class == null || same_pkg(cur_class, x.owner)) return null;
            if (x.name.equals("mc") && x.desc.equals("Lnet/minecraft/client/Minecraft;") && x.getOpcode() == Opcodes.GETFIELD) {
                MethodNode g = stubs.get("mc");
                if (g == null) {

                    g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mc", "(Ljava/lang/Object;)Lnet/minecraft/client/Minecraft;", null, null);
                    g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getMinecraft", "()Lnet/minecraft/client/Minecraft;", false));
                    g.instructions.add(new InsnNode(Opcodes.ARETURN));
                    stubs.put("mc", g);
                }
                rewired++;
                return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, "mc", g.desc, false);
            }
            if (x.getOpcode() == Opcodes.GETFIELD && !x.desc.startsWith("[") && x.desc.length() > 1) {

                String key = "r " + x.owner + "." + x.name;
                MethodNode g = stubs.get(key);
                if (g == null) {
                    g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "r" + stubs.size() + "_" + x.name, "(Ljava/lang/Object;)" + x.desc, null, null);
                    g.instructions.add(new LdcInsnNode(Type.getObjectType(x.owner)));
                    g.instructions.add(new LdcInsnNode(x.name));
                    g.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Miss", "read", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false));
                    g.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(x.desc).getInternalName()));
                    g.instructions.add(new InsnNode(Opcodes.ARETURN));
                    stubs.put(key, g);
                }
                rewired++;
                return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
            }
            return null;
        }
        if (x.owner.equals("net/minecraft/client/option/GameSettings") && x.getOpcode() == Opcodes.GETSTATIC) {
            String camel = camel(x.name);
            if (has_field(x.owner, camel, x.desc)) {
                String key = "o " + camel + x.desc;
                MethodNode g = stubs.get(key);
                if (g == null) {
                    g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "o" + stubs.size() + "_" + camel, "()" + x.desc, null, null);
                    g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getMinecraft", "()Lnet/minecraft/client/Minecraft;", false));
                    g.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "gameSettings", "Lnet/minecraft/client/option/GameSettings;"));
                    g.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, x.owner, camel, x.desc));
                    g.instructions.add(new InsnNode(Opcodes.ARETURN));
                    stubs.put(key, g);
                }
                rewired++;
                return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
            }
        }

        for (String anc : ancestors(x.owner)) {
            String sh = "openhouse/v7/S" + anc.substring(anc.lastIndexOf('/') + 1);
            ClassNode sc = shim.get(sh);
            if (sc == null) continue;
            String want = x.getOpcode() == Opcodes.GETSTATIC ? "()" + x.desc : x.getOpcode() == Opcodes.GETFIELD ? "(L" + anc + ";)" + x.desc
                    : x.getOpcode() == Opcodes.PUTFIELD ? "(L" + anc + ";" + x.desc + ")V" : "(" + x.desc + ")V";
            String nm = (x.getOpcode() == Opcodes.GETSTATIC || x.getOpcode() == Opcodes.GETFIELD ? "get_" : "set_") + x.name;
            for (MethodNode m : sc.methods) if (m.name.equals(nm) && m.desc.equals(want)) {
                rewired++;
                return new MethodInsnNode(Opcodes.INVOKESTATIC, sh, m.name, m.desc, false);
            }
        }
        AbstractInsnNode cv = convert_read(x);
        if (cv != null) return cv;
        AbstractInsnNode rt = retyped_read(x);
        if (rt != null) return rt;
        AbstractInsnNode cs = constant(x);
        if (cs != null) return cs;
        AbstractInsnNode st = same_type_field(x);
        if (st != null) return st;
        unresolved.merge("field " + x.owner + "." + x.name + " " + x.desc, 1, Integer::sum);
        return stub_field(x);
    }

    static Map<String, String[]> CONV;
    String[] converter(String from, String to) {
        if (CONV == null) {
            Map<String, String[]> m = new HashMap<>();
            for (ClassNode c : shim.values()) {
                if (!c.name.startsWith("openhouse/v7/conv/")) continue;
                for (MethodNode mn : c.methods) {
                    if ((mn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) != (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) continue;
                    Type[] a = Type.getArgumentTypes(mn.desc);
                    if (a.length != 1) continue;
                    m.put(a[0].getDescriptor() + ">" + Type.getReturnType(mn.desc).getDescriptor(), new String[] { c.name, mn.name, mn.desc });

                    if (mn.name.startsWith("from_") && a[0].getDescriptor().equals("Ljava/lang/Object;")) m.put("N:" + mn.name.substring(5) + ">" + Type.getReturnType(mn.desc).getDescriptor(), new String[] { c.name, mn.name, mn.desc });
                }
            }
            CONV = m;
        }
        String[] cv = CONV.get(from + ">" + to);
        if (cv == null && from.startsWith("L") && !known_class(from.substring(1, from.length() - 1))) cv = CONV.get("N:" + from.substring(from.lastIndexOf('/') + 1, from.length() - 1) + ">" + to);
        return cv;
    }

    AbstractInsnNode constant(FieldInsnNode x) {
        if (x.getOpcode() != Opcodes.GETSTATIC) return null;
        List<String> owners = new ArrayList<>(Collections.singletonList(x.owner));
        Type t = Type.getType(x.desc);
        if (t.getSort() == Type.OBJECT && !owners.contains(t.getInternalName())) owners.add(t.getInternalName());
        Set<String> names = new LinkedHashSet<>(Arrays.asList(x.name, camel(x.name), x.name.toLowerCase(Locale.ROOT)));
        for (String o : owners) {
            ClassNode c = g7.get(o);
            if (c == null) continue;
            for (String n : names) for (FieldNode f : c.fields)
                if (f.name.equals(n) && f.desc.equals(x.desc) && (f.access & Opcodes.ACC_STATIC) != 0) {
                    open_field(f, o, false);
                    rewired++;
                    return new FieldInsnNode(Opcodes.GETSTATIC, o, n, x.desc);
                }
        }
        return null;
    }

    AbstractInsnNode same_type_field(FieldInsnNode x) {
        boolean st8 = x.getOpcode() == Opcodes.GETSTATIC || x.getOpcode() == Opcodes.PUTSTATIC;
        String rn = renamed_field(g7, x.owner, x.name, x.desc, st8);
        if (rn != null) {
            for (String o = x.owner; o != null && g7.containsKey(o); o = g7.get(o).superName)
                for (FieldNode f : g7.get(o).fields) if (f.name.equals(rn) && f.desc.equals(x.desc)) {
                    open_field(f, o, x.getOpcode() == Opcodes.PUTFIELD || x.getOpcode() == Opcodes.PUTSTATIC);
                    rewired++;
                    return new FieldInsnNode(x.getOpcode(), o, f.name, f.desc);
                }
        }
        Type t = Type.getType(x.desc);
        if (t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY || x.desc.equals("Ljava/lang/String;")) return null;
        boolean stat = x.getOpcode() == Opcodes.GETSTATIC || x.getOpcode() == Opcodes.PUTSTATIC;
        FieldNode only = null;
        String in = null;
        int n = 0;
        for (String o = x.owner; o != null && g7.containsKey(o); o = g7.get(o).superName)
            for (FieldNode f : g7.get(o).fields) if (f.desc.equals(x.desc) && ((f.access & Opcodes.ACC_STATIC) != 0) == stat) { only = f; in = o; n++; }
        if (n != 1) return null;
        open_field(only, in, x.getOpcode() == Opcodes.PUTFIELD || x.getOpcode() == Opcodes.PUTSTATIC);
        rewired++;
        return new FieldInsnNode(x.getOpcode(), in, only.name, only.desc);
    }

    AbstractInsnNode retyped_read(FieldInsnNode x) {
        boolean stat = x.getOpcode() == Opcodes.GETSTATIC;
        if (!stat && x.getOpcode() != Opcodes.GETFIELD) return null;
        Type t8 = Type.getType(x.desc);
        if (t8.getSort() != Type.OBJECT) return null;
        for (String o = x.owner; o != null && g7.containsKey(o); o = g7.get(o).superName)
            for (FieldNode f : g7.get(o).fields) {
                if (!f.name.equals(x.name) || ((f.access & Opcodes.ACC_STATIC) != 0) != stat) continue;
                Type t7 = Type.getType(f.desc);
                if (t7.getSort() != Type.OBJECT) return null;
                String a = t7.getInternalName(), b = t8.getInternalName();
                boolean wide = sub_type(a, b);

                if (!wide && !((g7.containsKey(b) || shim.containsKey(b)) && sub_type(b, a))) return null;
                open_field(f, o, false);
                rewired++;
                if (wide) return new FieldInsnNode(x.getOpcode(), o, f.name, f.desc);
                String key = "n " + o + "." + f.name;
                MethodNode g = stubs.get(key);
                if (g == null) {
                    g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "n" + stubs.size() + "_" + f.name, (stat ? "()" : "(L" + o + ";)") + x.desc, null, null);
                    if (!stat) g.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    g.instructions.add(new FieldInsnNode(x.getOpcode(), o, f.name, f.desc));
                    g.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, b));
                    g.instructions.add(new InsnNode(Opcodes.ARETURN));
                    g.maxStack = 1;
                    g.maxLocals = stat ? 0 : 1;
                    stubs.put(key, g);
                }
                return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
            }
        return null;
    }

    boolean sub_type(String from, String to) {
        if (from.equals(to) || to.equals("java/lang/Object")) return true;
        if (game(from) || game(to)) return ancestors(from).contains(to);

        if (from.startsWith("it/unimi/dsi/fastutil/") && to.startsWith("java/util/")) {
            String k = from.endsWith("List") ? "java/util/List" : from.endsWith("Set") ? "java/util/Set" : from.endsWith("Map") ? "java/util/Map" : null;
            if (k == null) return false;
            from = k;
        }
        if (!from.startsWith("java/") || !to.startsWith("java/")) return false;
        try { return Class.forName(to.replace('/', '.'), false, null).isAssignableFrom(Class.forName(from.replace('/', '.'), false, null)); }
        catch (Throwable e) { return false; }
    }

    AbstractInsnNode convert_read(FieldInsnNode x) {
        boolean stat = x.getOpcode() == Opcodes.GETSTATIC;
        if (!stat && x.getOpcode() != Opcodes.GETFIELD) return null;
        FieldNode f7 = null;
        String in7 = null;
        for (String o = x.owner; o != null && f7 == null; ) {
            ClassNode c = g7.get(o);
            if (c == null) break;
            for (FieldNode f : c.fields) if (f.name.equals(x.name) && ((f.access & Opcodes.ACC_STATIC) != 0) == stat) { f7 = f; in7 = o; }
            o = c.superName;
        }
        if (f7 == null) return null;
        String[] cv = converter(f7.desc, x.desc);
        if (cv == null) return null;
        String key = "v " + x.owner + "." + x.name + x.desc;
        MethodNode g = stubs.get(key);
        if (g == null) {
            g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "v" + stubs.size() + "_" + x.name, stat ? "()" + x.desc : "(L" + x.owner + ";)" + x.desc, null, null);
            if (!stat) g.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.instructions.add(new FieldInsnNode(x.getOpcode(), in7, f7.name, f7.desc));
            g.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cv[0], cv[1], cv[2], false));
            g.instructions.add(new InsnNode(Opcodes.ARETURN));
            stubs.put(key, g);

            if ((f7.access & Opcodes.ACC_PUBLIC) == 0) widen.add("accessible field " + in7 + " " + f7.name + " " + f7.desc);
        }
        rewired++;
        return new MethodInsnNode(Opcodes.INVOKESTATIC, gen_class, g.name, g.desc, false);
    }

    static boolean drops_last(String d8, String d7) {
        Type[] a = Type.getArgumentTypes(d8);
        if (a.length == 0 || a[a.length - 1].getSort() != Type.INT) return false;
        String cut = d8.replace(POSC, "III").replace(POS, "III");
        cut = cut.substring(0, cut.lastIndexOf(")") - 1) + cut.substring(cut.lastIndexOf(")"));
        return cut.equals(d7) || d7.equals(block_ids(cut));
    }

    static String minus_last_int(String d) {
        int r = d.lastIndexOf(')');
        return r > 1 && d.charAt(r - 1) == 'I' && (r < 2 || d.charAt(r - 2) != '[') ? d.substring(0, r - 1) + d.substring(r) : null;
    }

    MethodNode adapter(MethodInsnNode x, String n7, String d7) {
        boolean stat = x.getOpcode() == Opcodes.INVOKESTATIC;
        String ad = stat ? x.desc : "(L" + x.owner + ";" + x.desc.substring(1);
        String key = x.owner + "." + x.name + x.desc + (stat ? "S" : "");
        MethodNode have = adapters.get(key);
        if (have != null) return have;
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "a" + adapters.size() + "_" + x.name, ad, null, null);
        Type[] args = Type.getArgumentTypes(ad);
        int slot = 0;
        boolean drop = drops_last(x.desc, d7), ids = wants_ids(x.desc, d7);
        for (int ai = 0; ai < args.length; ai++) {
            Type t = args[ai];
            if (drop && ai == args.length - 1) { slot += t.getSize(); continue; }
            if (t.getDescriptor().equals(POSC) || t.getDescriptor().equals(POS)) {
                String io = t.getInternalName();
                for (String axis : new String[] { "x", "y", "z" }) {
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
                    m.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/core/world/pos/TilePosc", axis, "()I", true));
                }
            } else if (ids && t.getDescriptor().equals(BLOCK) && (stat || ai > 0)) {
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/SBlock", "id_of", "(Lnet/minecraft/core/block/Block;)I", false));
            } else m.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            slot += t.getSize();
        }
        int op = x.getOpcode();
        m.instructions.add(new MethodInsnNode(op, x.owner, n7, d7, x.itf));
        m.instructions.add(new InsnNode(Type.getReturnType(ad).getOpcode(Opcodes.IRETURN)));
        m.maxLocals = slot;
        m.maxStack = slot * 3 + 4;
        adapters.put(key, m);
        return m;
    }

    void open_gen(ClassNode c) {
        for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode) {
                MethodInsnNode x = (MethodInsnNode) i;
                if (x.owner.startsWith("[") || !game(x.owner)) continue;
                open_class(x.owner);
                MethodNode t = find(x.owner, x.name, x.desc);
                if (t == null || t == OBJ_ANY || !g7.containsKey(found_in)) continue;
                open_class(found_in);
                if ((t.access & Opcodes.ACC_PUBLIC) != 0) continue;
                widen.add("accessible method " + found_in + " " + t.name + " " + t.desc);
                if ((t.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == Opcodes.ACC_PRIVATE && !t.name.equals("<init>")) widen.add("extendable method " + found_in + " " + t.name + " " + t.desc);
            } else if (i instanceof FieldInsnNode) {
                FieldInsnNode x = (FieldInsnNode) i;
                if (!game(x.owner)) continue;
                open_class(x.owner);
                FieldNode f = field_node(x.owner, x.name, x.desc);
                if (f == null || !g7.containsKey(field_in)) continue;
                open_class(field_in);
                if ((f.access & Opcodes.ACC_PUBLIC) == 0) widen.add("accessible field " + field_in + " " + f.name + " " + f.desc);
                if ((x.getOpcode() == Opcodes.PUTFIELD || x.getOpcode() == Opcodes.PUTSTATIC) && (f.access & Opcodes.ACC_FINAL) != 0) widen.add("mutable field " + field_in + " " + f.name + " " + f.desc);
            } else if (i instanceof TypeInsnNode) {
                String d = ((TypeInsnNode) i).desc;
                if (!d.startsWith("[")) open_class(d);
            }
        }
    }
    void open_class(String n) {
        ClassNode k = g7.get(n);
        if (k != null && (k.access & Opcodes.ACC_PUBLIC) == 0) widen.add("accessible class " + n);
    }

    byte[] gen() {
        ClassNode c = new ClassNode();
        c.version = Opcodes.V17;
        c.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        c.name = gen_class;
        c.superName = "java/lang/Object";
        c.methods.addAll(adapters.values());
        c.methods.addAll(stubs.values());
        open_gen(c);
        if (!hold_fields.isEmpty()) {
            MethodNode ci = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            for (Map.Entry<String, String> f : hold_fields.entrySet()) {
                c.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, f.getKey(), f.getValue(), null, null));

                String col = f.getValue().equals("Ljava/util/List;") || f.getValue().equals("Ljava/util/Collection;") ? "java/util/ArrayList"
                        : f.getValue().equals("Ljava/util/Map;") ? "java/util/HashMap" : f.getValue().equals("Ljava/util/Set;") ? "java/util/HashSet" : null;

                if (col == null && f.getValue().startsWith("L")) {
                    String t = f.getValue().substring(1, f.getValue().length() - 1);
                    ClassNode tc = g7.containsKey(t) ? g7.get(t) : shim.get(t);
                    if (tc != null && (tc.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0)
                        for (MethodNode mm : tc.methods) if (mm.name.equals("<init>") && mm.desc.equals("()V") && (mm.access & Opcodes.ACC_PUBLIC) != 0) col = t;

                    if (col == null && STUBS.containsKey(t) && !STUBS.get(t).itf) { STUBS.get(t).methods.put("<init>()V", Opcodes.INVOKESPECIAL); col = t; }
                }
                if (col == null) continue;

                LabelNode t0 = new LabelNode(), t1 = new LabelNode(), th = new LabelNode(), done = new LabelNode();
                ci.instructions.add(t0);
                ci.instructions.add(new TypeInsnNode(Opcodes.NEW, col));
                ci.instructions.add(new InsnNode(Opcodes.DUP));
                ci.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, col, "<init>", "()V", false));
                ci.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, c.name, f.getKey(), f.getValue()));
                ci.instructions.add(t1);
                ci.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
                ci.instructions.add(th);
                ci.instructions.add(new InsnNode(Opcodes.POP));
                ci.instructions.add(done);
                ci.tryCatchBlocks.add(new TryCatchBlockNode(t0, t1, th, "java/lang/Throwable"));
            }
            ci.instructions.add(new InsnNode(Opcodes.RETURN));
            c.methods.add(ci);
        }
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return common(a, b); }
        };
        c.accept(w);
        return w.toByteArray();
    }

    static boolean is_mixin(ClassNode c) {
        if (c.invisibleAnnotations != null) for (AnnotationNode a : c.invisibleAnnotations) if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return true;
        if (c.visibleAnnotations != null) for (AnnotationNode a : c.visibleAnnotations) if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return true;
        return false;
    }

    static final Map<String, String> MIXIN_SELECTORS = new HashMap<>();
    static {
        String pc = "net/minecraft/client/net/handler/PacketHandlerClient.";
        MIXIN_SELECTORS.put(pc + "handleMoveEntity", "handleEntity");
        MIXIN_SELECTORS.put(pc + "handleTeleportEntity", "handleEntityTeleport");
        MIXIN_SELECTORS.put(pc + "handleAddEntity", "handleVehicleSpawn");
        MIXIN_SELECTORS.put("net/minecraft/server/net/handler/PacketHandlerServer.handleMessage", "handleChat");
        MIXIN_SELECTORS.put("net/minecraft/server/net/handler/PacketHandlerServer.handlePlayerAction", "handleBlockDig");
        MIXIN_SELECTORS.put("net/minecraft/client/sound/SoundEngine.playBackgroundSound", "tick");
        MIXIN_SELECTORS.put("net/minecraft/client/sound/SoundEngine.tickBackgroundMusic", "tick");

        String mrp = "net/minecraft/client/render/entity/MobRendererPlayer.", tg = "Lnet/minecraft/client/render/tessellator/TessellatorGeneral;",
                pl = "Lnet/minecraft/core/entity/player/Player;";
        MIXIN_SELECTORS.put(mrp + "renderAdditional(" + tg + pl + "F)V", "renderSpecials(" + pl + "F)V");
        MIXIN_SELECTORS.put(mrp + "renderSpecials(" + tg + pl + "DDD)V", "renderSpecials(Lnet/minecraft/client/render/tessellator/Tessellator;" + pl + "DDD)V");

        MIXIN_SELECTORS.put("net/minecraft/client/Minecraft.startWorld(Ljava/lang/String;)V", "startWorld(Ljava/lang/String;Ljava/lang/String;J)V");

        for (String d : new String[] { "block/model/BlockModelDispatcher", "item/model/ItemModelDispatcher", "block/color/BlockColorDispatcher", "EntityRenderDispatcher", "TileEntityRenderDispatcher" })
            MIXIN_SELECTORS.put("net/minecraft/client/render/" + d + ".reload", "<init>");
    }

    String mixin_target(ClassNode c) {
        for (List<AnnotationNode> l : Arrays.asList(c.visibleAnnotations, c.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l)
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") && a.values != null) for (int i = 0; i < a.values.size(); i += 2) {
                Object v = a.values.get(i + 1);
                if (a.values.get(i).equals("value") && v instanceof List && !((List<?>) v).isEmpty()) return moved(((Type) ((List<?>) v).get(0)).getInternalName());
                if (a.values.get(i).equals("targets") && v instanceof List && !((List<?>) v).isEmpty()) return moved(((String) ((List<?>) v).get(0)).replace('.', '/'));
            }
        return null;
    }

    void reselect(ClassNode c) {
        String tgt = mixin_target(c);
        if (tgt == null) return;
        List<MethodNode> add = new ArrayList<>();
        for (MethodNode m : new ArrayList<>(c.methods)) for (int vis = 0; vis < 2; vis++) {
            List<AnnotationNode> l = vis == 0 ? m.visibleAnnotations : m.invisibleAnnotations;
            if (l == null) continue;
            for (AnnotationNode a : new ArrayList<>(l)) {
                if (!a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") || a.values == null) continue;
                for (int i = 0; i < a.values.size(); i += 2) {
                    if (!"method".equals(a.values.get(i)) || !(a.values.get(i + 1) instanceof List)) continue;
                    List<Object> sels = new ArrayList<>((List<?>) a.values.get(i + 1));
                    String from = null, to = null;
                    for (int k = 0; k < sels.size(); k++) {
                        String sel = (String) sels.get(k), n7 = MIXIN_SELECTORS.get(tgt + "." + sel);
                        if (n7 == null && !sel.contains("(") && a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                            String args = target_args(m);
                            if (args != null) {
                                String key = tgt + "." + sel + "(" + args + ")";
                                for (Map.Entry<String, String> e : MIXIN_SELECTORS.entrySet()) if (e.getKey().startsWith(key)) { n7 = e.getValue(); sel = sel + e.getKey().substring(key.length() - args.length() - 2); }
                                if (n7 == null && (args.contains(POS) || args.contains(POSC))) {

                                    String want = "(" + args.replace(POSC, "III").replace(POS, "III") + ")";
                                    ClassNode tc = g7.get(tgt);
                                    if (tc != null) for (MethodNode tm : tc.methods) if (tm.name.equals(sel) && tm.desc.startsWith(want)) { n7 = sel + tm.desc; sel = sel + "(" + args + ")" + Type.getReturnType(tm.desc).getDescriptor(); }
                                }
                            }
                        }
                        if (n7 == null) continue;
                        sels.set(k, n7);
                        rewired++;
                        if (sel.contains("(") && n7.contains("(") && !sel.substring(sel.indexOf('(')).equals(n7.substring(n7.indexOf('(')))) { from = sel; to = n7; }
                    }
                    a.values.set(i + 1, sels);
                    if (from == null || !a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                    MethodNode ad = inject_adapter(c, m, from, to);
                    if (ad == null) continue;
                    l.remove(a);
                    if (vis == 0) { if (ad.visibleAnnotations == null) ad.visibleAnnotations = new ArrayList<>(); ad.visibleAnnotations.add(a); }
                    else { if (ad.invisibleAnnotations == null) ad.invisibleAnnotations = new ArrayList<>(); ad.invisibleAnnotations.add(a); }
                    add.add(ad);
                }
            }
        }
        c.methods.addAll(add);
    }

    static String target_args(MethodNode m) {
        Type[] h = Type.getArgumentTypes(m.desc);
        StringBuilder b = new StringBuilder();
        for (Type t : h) {
            String d = t.getDescriptor();
            if (d.startsWith("Lorg/spongepowered/asm/mixin/injection/callback/")) return b.length() == 0 ? null : b.toString();
            b.append(d);
        }
        return null;
    }

    MethodNode inject_adapter(ClassNode c, MethodNode m, String s8, String s7) {
        Type[] a8 = Type.getArgumentTypes(s8.substring(s8.indexOf('('))), a7 = Type.getArgumentTypes(s7.substring(s7.indexOf('('))), h = Type.getArgumentTypes(m.desc);
        if (h.length < a8.length + 1) return null;
        for (int i = 0; i < a8.length; i++) if (!h[i].equals(a8[i])) return null;
        List<Type> nd = new ArrayList<>(Arrays.asList(a7));
        for (int i = a8.length; i < h.length; i++) nd.add(h[i]);
        boolean stat = (m.access & Opcodes.ACC_STATIC) != 0;
        MethodNode ad = new MethodNode(Opcodes.ACC_PRIVATE | (stat ? Opcodes.ACC_STATIC : 0), m.name + "$v7", Type.getMethodDescriptor(Type.getReturnType(m.desc), nd.toArray(new Type[0])), null, null);
        int slot = stat ? 0 : 1;
        int[] at7 = new int[a7.length];
        for (int i = 0; i < a7.length; i++) { at7[i] = slot; slot += a7[i].getSize(); }
        InsnList il = ad.instructions;
        if (!stat) il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int p = 0;
        for (int i = 0; i < a8.length; i++) {
            String hd = h[i].getDescriptor();
            if ((hd.equals(POS) || hd.equals(POSC)) && p + 2 < a7.length && a7[p].getSort() == Type.INT && a7[p + 1].getSort() == Type.INT && a7[p + 2].getSort() == Type.INT) {
                il.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/core/world/pos/TilePos"));
                il.add(new InsnNode(Opcodes.DUP));
                for (int q = 0; q < 3; q++) il.add(new VarInsnNode(Opcodes.ILOAD, at7[p + q]));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/core/world/pos/TilePos", "<init>", "(III)V", false));
                p += 3;
                continue;
            }
            int got = -1;
            for (int j = p; j < a7.length && got < 0; j++) if (a7[j].equals(h[i])) got = j;
            if (got >= 0) { il.add(new VarInsnNode(h[i].getOpcode(Opcodes.ILOAD), at7[got])); p = got + 1; }
            else switch (h[i].getSort()) {
                case Type.LONG: il.add(new InsnNode(Opcodes.LCONST_0)); break;
                case Type.FLOAT: il.add(new InsnNode(Opcodes.FCONST_0)); break;
                case Type.DOUBLE: il.add(new InsnNode(Opcodes.DCONST_0)); break;
                case Type.OBJECT: case Type.ARRAY: il.add(new InsnNode(Opcodes.ACONST_NULL)); break;
                default: il.add(new InsnNode(Opcodes.ICONST_0));
            }
        }
        for (int i = a8.length; i < h.length; i++) { il.add(new VarInsnNode(h[i].getOpcode(Opcodes.ILOAD), slot)); slot += h[i].getSize(); }
        il.add(new MethodInsnNode(stat ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, c.name, m.name, m.desc, false));
        il.add(new InsnNode(Type.getReturnType(m.desc).getOpcode(Opcodes.IRETURN)));
        ad.maxLocals = slot;
        ad.maxStack = slot + 4;
        rewired++;
        return ad;
    }

    static final java.util.regex.Pattern LNAME = java.util.regex.Pattern.compile("L(net/minecraft/[A-Za-z0-9_/$]+);");
    void rename_strings(AnnotationNode a) {
        if (a.values == null) return;
        for (int i = 1; i < a.values.size(); i += 2) a.values.set(i, rename_value(a.values.get(i)));
    }
    Object rename_value(Object v) {
        if (v instanceof String) {
            java.util.regex.Matcher mt = LNAME.matcher((String) v);
            StringBuffer sb = new StringBuffer();
            while (mt.find()) mt.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("L" + moved(mt.group(1)) + ";"));
            mt.appendTail(sb);
            return sb.toString();
        }
        if (v instanceof AnnotationNode) { rename_strings((AnnotationNode) v); return v; }
        if (v instanceof List) { List<Object> l = new ArrayList<>((List<?>) v); for (int i = 0; i < l.size(); i++) l.set(i, rename_value(l.get(i))); return l; }
        return v;
    }

    static final List<String> DEAD_HOOKS = new ArrayList<>();
    void hooks(ClassNode c) {
        List<String> targets = new ArrayList<>();
        for (List<AnnotationNode> l : Arrays.asList(c.visibleAnnotations, c.invisibleAnnotations)) {
            if (l == null) continue;
            for (AnnotationNode a : l) if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") && a.values != null)
                for (int i = 0; i < a.values.size(); i += 2) {
                    Object v = a.values.get(i + 1);
                    if (a.values.get(i).equals("value") && v instanceof List) for (Object t : (List<?>) v) targets.add(((Type) t).getInternalName());
                    if (a.values.get(i).equals("targets") && v instanceof List) for (Object t : (List<?>) v) targets.add(((String) t).replace('.', '/'));
                }
        }
        for (String t : targets) {
            ClassNode tc = g7.get(t);
            if (tc == null) { DEAD_HOOKS.add(c.name + ": target class " + t + " not in 7.3"); continue; }
            for (MethodNode m : c.methods) for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (l == null) continue;
                for (AnnotationNode a : l) {
                    if (!a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") || a.values == null) continue;
                    for (int i = 0; i < a.values.size(); i += 2) {
                        if (!a.values.get(i).equals("method") || !(a.values.get(i + 1) instanceof List)) continue;
                        for (Object sel : (List<?>) a.values.get(i + 1)) {
                            String s = (String) sel, nm = s.contains("(") ? s.substring(0, s.indexOf('(')) : s.replace("*", ""), ds = s.contains("(") ? s.substring(s.indexOf('(')) : null;
                            boolean hit = false;
                            for (MethodNode tm : tc.methods) if (tm.name.equals(nm) && (ds == null || tm.desc.equals(ds))) hit = true;
                            if (!hit) DEAD_HOOKS.add(c.name + "." + m.name + ": " + t.substring(t.lastIndexOf('/') + 1) + "." + s + " not in 7.3");
                        }
                    }
                }
            }
        }
    }

    boolean wrap(ClassNode c) { return wrap(c, false); }

    boolean wrap(ClassNode c, boolean every) {
        boolean any = false;
        for (MethodNode m : c.methods) {
            if (m.instructions == null || m.instructions.size() == 0 || !Type.getReturnType(m.desc).equals(Type.VOID_TYPE) || m.name.startsWith("<")) continue;
            boolean inject = every;
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
                if (l != null) for (AnnotationNode a : l) if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) inject = true;
            if (!inject) continue;
            LabelNode s0 = new LabelNode(), s1 = new LabelNode(), h = new LabelNode();
            m.instructions.insert(s0);
            m.instructions.add(s1);
            m.instructions.add(h);
            m.instructions.add(new LdcInsnNode(c.name.substring(c.name.lastIndexOf('/') + 1) + "." + m.name));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Miss", "caught", "(Ljava/lang/Throwable;Ljava/lang/String;)V", false));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            m.tryCatchBlocks.add(new TryCatchBlockNode(s0, s1, h, "java/lang/Throwable"));

            for (AbstractInsnNode i : m.instructions.toArray()) if (i instanceof FrameNode) m.instructions.remove(i);
            any = true;
        }
        return any;
    }

    String common(String a, String b) {
        List<String> ca = chain(a), cb = chain(b);
        for (String x : ca) if (cb.contains(x)) return x;
        return "java/lang/Object";
    }
    List<String> chain(String n) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (n != null && seen.add(n)) {
            out.add(n);
            ClassNode c = own.containsKey(n) ? own.get(n) : g7.containsKey(n) ? g7.get(n) : shim.get(n);
            if (c == null && (n.startsWith("java/") || n.startsWith("javax/"))) {

                try {
                    Class<?> k = Class.forName(n.replace('/', '.'), false, null);
                    n = k.getSuperclass() == null ? null : k.getSuperclass().getName().replace('.', '/');
                    continue;
                } catch (Throwable ignored) { }
            }
            n = c == null ? null : c.superName;
        }
        return out;
    }

    static final List<String> GAME_JARS = new ArrayList<>();
    static final Map<String, ClassNode> CODE7 = new HashMap<>();
    static synchronized ClassNode code7(String owner) {
        if (CODE7.containsKey(owner)) return CODE7.get(owner);
        ClassNode c = null;
        for (String j : GAME_JARS) {
            try (ZipFile z = new ZipFile(j)) {
                ZipEntry e = z.getEntry(owner + ".class");
                if (e == null) continue;
                c = new ClassNode();
                new ClassReader(z.getInputStream(e)).accept(c, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                break;
            } catch (IOException ignored) { }
        }
        CODE7.put(owner, c);
        return c;
    }

    static final String WRAP_OP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
    void moved_wraps(ClassNode c) {
        if (cur_class == null || GAME_JARS.isEmpty()) return;
        ClassNode t = code7(cur_class);
        if (t == null) return;
        List<MethodNode> add = new ArrayList<>();
        for (MethodNode m : c.methods) {
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (l == null) continue;
                for (Iterator<AnnotationNode> it = l.iterator(); it.hasNext(); ) {
                    AnnotationNode a = it.next();
                    if (!a.desc.equals(WRAP_OP) || a.values == null) continue;
                    List<?> sel = null, at = null;
                    for (int i = 0; i < a.values.size(); i += 2) {
                        if (a.values.get(i).equals("method")) sel = (List<?>) a.values.get(i + 1);
                        if (a.values.get(i).equals("at")) at = a.values.get(i + 1) instanceof List ? (List<?>) a.values.get(i + 1) : List.of(a.values.get(i + 1));
                    }
                    if (sel == null || at == null || at.isEmpty()) continue;
                    AnnotationNode an = (AnnotationNode) at.get(0);
                    String target = null;
                    for (int i = 0; an.values != null && i < an.values.size(); i += 2) if (an.values.get(i).equals("target")) target = (String) an.values.get(i + 1);
                    if (target == null || !target.startsWith("L") || !target.contains(";")) continue;
                    String towner = target.substring(1, target.indexOf(';'));
                    if (!target.substring(target.indexOf(';') + 1).contains("(")) continue;
                    String rest = target.substring(target.indexOf(';') + 1), tname = rest.substring(0, rest.indexOf('(')), tdesc = rest.substring(rest.indexOf('('));
                    MethodInsnNode ctor = null;
                    boolean has = false, one = true;
                    for (Object so : sel) {
                        String s = (String) so, nm = s.contains("(") ? s.substring(0, s.indexOf('(')) : s;
                        for (MethodNode tm : t.methods) if (tm.name.equals(nm) && tm.instructions != null) for (AbstractInsnNode x : tm.instructions) {
                            if (!(x instanceof MethodInsnNode mi) || !mi.owner.equals(towner)) continue;
                            if (mi.name.equals(tname) && mi.desc.equals(tdesc)) has = true;
                            else if (mi.name.equals("<init>")) { if (ctor != null) one = false; ctor = mi; }
                        }
                    }
                    if (has || ctor == null || !one) continue;
                    it.remove();
                    boolean stat = (m.access & Opcodes.ACC_STATIC) != 0;
                    MethodNode b = new MethodNode(Opcodes.ACC_PRIVATE | (stat ? Opcodes.ACC_STATIC : 0), "oh$moved_" + m.name, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
                    AnnotationNode inj = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
                    AnnotationNode at2 = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");

                    AbstractInsnNode nx = ctor.getNext();
                    while (nx != null && nx.getOpcode() < 0) nx = nx.getNext();
                    if (nx instanceof FieldInsnNode fs && (fs.getOpcode() == Opcodes.PUTFIELD || fs.getOpcode() == Opcodes.PUTSTATIC))
                        at2.values = new ArrayList<>(List.of("value", "FIELD", "target", "L" + fs.owner + ";" + fs.name + ":" + fs.desc, "opcode", fs.getOpcode(), "shift", new String[] { "Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER" }));
                    else at2.values = new ArrayList<>(List.of("value", "INVOKE", "target", "L" + towner + ";<init>" + ctor.desc, "shift", new String[] { "Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER" }));
                    inj.values = new ArrayList<>(List.of("method", new ArrayList<>(sel), "at", new ArrayList<>(List.of(at2)), "require", 0));
                    b.visibleAnnotations = new ArrayList<>(List.of(inj));
                    if (!stat) b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    for (Type p : Type.getArgumentTypes(m.desc)) {
                        if (p.getDescriptor().equals(OPERATION)) {
                            b.instructions.add(new TypeInsnNode(Opcodes.NEW, "openhouse/v7/NoOp"));
                            b.instructions.add(new InsnNode(Opcodes.DUP));
                            b.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "openhouse/v7/NoOp", "<init>", "()V", false));
                        } else b.instructions.add(zero(p));
                    }
                    b.instructions.add(new MethodInsnNode(stat ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, c.name, m.name, m.desc, false));
                    Type r = Type.getReturnType(m.desc);
                    if (r.getSize() == 1) b.instructions.add(new InsnNode(Opcodes.POP)); else if (r.getSize() == 2) b.instructions.add(new InsnNode(Opcodes.POP2));
                    b.instructions.add(new InsnNode(Opcodes.RETURN));
                    b.maxStack = Type.getArgumentsAndReturnSizes(m.desc) + 4;
                    b.maxLocals = stat ? 1 : 2;
                    add.add(b);
                    rewired++;
                }
            }
        }
        c.methods.addAll(add);
    }

    static AbstractInsnNode zero(Type p) {
        switch (p.getSort()) {
            case Type.LONG: return new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT: return new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE: return new InsnNode(Opcodes.DCONST_0);
            case Type.OBJECT: case Type.ARRAY: return new InsnNode(Opcodes.ACONST_NULL);
            default: return new InsnNode(Opcodes.ICONST_0);
        }
    }

    void soften(ClassNode c) {

        for (List<AnnotationNode> l : Arrays.asList(c.visibleAnnotations, c.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l)
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") && a.values != null) for (int i = 0; i < a.values.size(); i += 2)
                if (a.values.get(i).equals("targets") && a.values.get(i + 1) instanceof List) {
                    List<Object> t = new ArrayList<>((List<?>) a.values.get(i + 1));
                    for (int k = 0; k < t.size(); k++) t.set(k, moved(((String) t.get(k)).replace('.', '/')).replace('/', '.'));
                    a.values.set(i + 1, t);
                }
        reselect(c);
        for (MethodNode m : c.methods) for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
            if (l != null) for (AnnotationNode a : l) rename_strings(a);
        accessors(c);
        shadows(c);
        overwrites(c);
        moved_wraps(c);
        call_hooks(c);
        hooks(c);
        for (MethodNode m : c.methods) {
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (l == null) continue;
                for (AnnotationNode a : l) {
                    if (!a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") && !a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) continue;
                    if (a.values == null) a.values = new ArrayList<>();
                    set(a, "require", 0);
                    set(a, "expect", 0);
                }
            }
        }
    }

    static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;", INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    static Map<String, List<String[]>> G8F;
    static synchronized List<String[]> g8_fields(String owner) {
        if (G8F == null) {
            G8F = new HashMap<>();
            try (InputStream in = Vt.class.getResourceAsStream("/openhouse/v7/g8fields.txt")) {
                if (in != null) for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    String[] t = l.trim().split(" ");
                    List<String[]> fs = new ArrayList<>();
                    for (int i = 1; i < t.length; i++) { String[] p = t[i].split(":"); if (p.length >= 2) fs.add(new String[] { p[0], p[1], p.length > 2 ? "s" : "" }); }
                    G8F.put(t[0], fs);
                }
            } catch (IOException ignored) { }
        }
        List<String[]> l = G8F.get(owner);
        if (l == null && G8.containsKey(owner) && !G8.get(owner).fields.isEmpty()) {
            l = new ArrayList<>();
            for (FieldNode f : G8.get(owner).fields) l.add(new String[] { f.name, f.desc, (f.access & Opcodes.ACC_STATIC) != 0 ? "s" : "" });
        }
        return l;
    }

    static String renamed_field(Map<String, ClassNode> g7, String owner, String name, String desc, boolean stat) {
        for (String o = owner; o != null; ) {
            List<String[]> f8 = g8_fields(o);
            ClassNode c7 = g7.get(o);
            if (f8 != null && c7 != null) {
                boolean here = false;
                for (String[] f : f8) here |= f[0].equals(name) && f[1].equals(desc);
                if (here) {
                    Set<String> n7 = new HashSet<>(), n8 = new HashSet<>();
                    for (FieldNode f : c7.fields) n7.add(f.name);
                    for (String[] f : f8) n8.add(f[0]);
                    List<String> l8 = new ArrayList<>(), l7 = new ArrayList<>();
                    for (String[] f : f8) if (f[1].equals(desc) && f[2].isEmpty() != stat && !n7.contains(f[0])) l8.add(f[0]);
                    for (FieldNode f : c7.fields) if (f.desc.equals(desc) && ((f.access & Opcodes.ACC_STATIC) != 0) == stat && !n8.contains(f.name)) l7.add(f.name);
                    int i = l8.indexOf(name);
                    return i >= 0 && l8.size() == l7.size() ? l7.get(i) : null;
                }
            }
            ClassNode n = G8.get(o);
            o = n != null ? n.superName : c7 != null ? c7.superName : null;
        }
        return null;
    }
    void accessors(ClassNode c) {
        if ((c.access & Opcodes.ACC_INTERFACE) == 0 || cur_class == null) return;
        ClassNode t = g7.get(cur_class);
        List<MethodNode> dead = new ArrayList<>();
        for (MethodNode m : c.methods) {
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                if (l == null) continue;
                for (Iterator<AnnotationNode> it = l.iterator(); it.hasNext(); ) {
                    AnnotationNode a = it.next();
                    boolean acc = a.desc.equals(ACCESSOR);
                    if (!acc && !a.desc.equals(INVOKER)) continue;
                    String named = null;
                    if (a.values != null) for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals("value")) named = (String) a.values.get(i + 1);
                    boolean stat = (m.access & Opcodes.ACC_STATIC) != 0;
                    if (acc) {
                        Type[] args = Type.getArgumentTypes(m.desc);
                        String fd = args.length == 0 ? Type.getReturnType(m.desc).getDescriptor() : args[0].getDescriptor();
                        String fn = named != null ? named : accessor_field(m.name);
                        if (t == null || has_own_field(t, fn, fd)) continue;
                        String rn = renamed_field(g7, cur_class, fn, fd, stat);
                        if (rn != null) { if (a.values == null) a.values = new ArrayList<>(); set(a, "value", rn); rewired++; continue; }
                        FieldNode only = null;
                        int n = 0;
                        for (FieldNode f : t.fields) if (f.desc.equals(fd) && ((f.access & Opcodes.ACC_STATIC) != 0) == stat) { only = f; n++; }
                        if (n == 1) { if (a.values == null) a.values = new ArrayList<>(); set(a, "value", only.name); rewired++; continue; }
                    } else {
                        String mn = named != null ? named : accessor_method(m.name);

                        Type rt = Type.getReturnType(m.desc);
                        if (mn.equals("<init>") || stat && rt.getSort() == Type.OBJECT && rt.getInternalName().equals(cur_class)) continue;
                        if (t == null || find(cur_class, mn, m.desc) != null) continue;
                    }

                    dead.add(m);
                    DEAD_ACC.add(c.name + "." + m.name + m.desc);
                    stubbed++;
                }
            }
        }
        c.methods.removeAll(dead);
    }

    static final Set<String> DEAD_ACC = java.util.concurrent.ConcurrentHashMap.newKeySet();

    boolean accessor_dead(ClassNode iface, MethodNode m) {
        if (DEAD_ACC.contains(iface.name + "." + m.name + m.desc)) return true;
        String tgt = null;
        for (List<AnnotationNode> l : Arrays.asList(iface.visibleAnnotations, iface.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l)
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") && a.values != null) for (int i = 0; i < a.values.size(); i += 2)
                if (a.values.get(i).equals("value") && a.values.get(i + 1) instanceof List && !((List<?>) a.values.get(i + 1)).isEmpty())
                    tgt = ((Type) ((List<?>) a.values.get(i + 1)).get(0)).getInternalName();
        ClassNode t = tgt == null ? null : g7.get(tgt);
        if (t == null) return false;
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
            if (l == null) continue;
            for (AnnotationNode a : l) {
                boolean acc = a.desc.equals(ACCESSOR);
                if (!acc && !a.desc.equals(INVOKER)) continue;
                String named = null;
                if (a.values != null) for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals("value")) named = (String) a.values.get(i + 1);
                boolean stat = (m.access & Opcodes.ACC_STATIC) != 0;
                if (acc) {
                    Type[] args = Type.getArgumentTypes(m.desc);
                    String fd = args.length == 0 ? Type.getReturnType(m.desc).getDescriptor() : args[0].getDescriptor();
                    String fn = named != null ? named : accessor_field(m.name);
                    if (has_own_field(t, fn, fd) || renamed_field(g7, tgt, fn, fd, stat) != null) return false;
                    int n = 0;
                    for (FieldNode f : t.fields) if (f.desc.equals(fd) && ((f.access & Opcodes.ACC_STATIC) != 0) == stat) n++;
                    return n != 1;
                }
                String mn = named != null ? named : accessor_method(m.name);
                Type rt = Type.getReturnType(m.desc);
                if (mn.equals("<init>") || stat && rt.getSort() == Type.OBJECT && rt.getInternalName().equals(tgt)) return false;
                return find(tgt, mn, m.desc) == null;
            }
        }
        return false;
    }

    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;", UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    void shadows(ClassNode c) {
        if ((c.access & Opcodes.ACC_INTERFACE) != 0 || cur_class == null || !g7.containsKey(cur_class)) return;
        Map<String, String> renamed = new HashMap<>();
        for (FieldNode f : c.fields) {
            AnnotationNode sh = shadow_of(f.visibleAnnotations, f.invisibleAnnotations);
            if (sh == null) continue;
            boolean stat = (f.access & Opcodes.ACC_STATIC) != 0;
            if (has_field(cur_class, f.name, f.desc)) { if (flip_static(c, f, stat)) rewired++; continue; }
            FieldNode only = null;
            int n = 0;
            for (String o = cur_class; o != null && g7.containsKey(o); o = g7.get(o).superName)
                for (FieldNode t : g7.get(o).fields) if (t.desc.equals(f.desc) && ((t.access & Opcodes.ACC_STATIC) != 0) == stat) { only = t; n++; }
            String rn = n == 1 ? null : renamed_field(g7, cur_class, f.name, f.desc, stat);
            if (n == 1) { renamed.put(f.name + " " + f.desc, only.name); f.name = only.name; rewired++; }
            else if (rn != null) { renamed.put(f.name + " " + f.desc, rn); f.name = rn; rewired++; }
            else if (retyped_shadow(c, f, stat)) rewired++;
            else {

                f.access = f.access & ~(Opcodes.ACC_FINAL | Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
                sh.desc = UNIQUE; sh.values = null; stubbed++;
            }
        }
        if (!renamed.isEmpty()) for (MethodNode m : c.methods) if (m.instructions != null) for (AbstractInsnNode in : m.instructions.toArray())
            if (in instanceof FieldInsnNode && ((FieldInsnNode) in).owner.equals(c.name)) {
                String r = renamed.get(((FieldInsnNode) in).name + " " + ((FieldInsnNode) in).desc);
                if (r != null) ((FieldInsnNode) in).name = r;
            }
        for (MethodNode m : c.methods) {
            if (m.name.startsWith("<")) continue;
            AnnotationNode sh = shadow_of(m.visibleAnnotations, m.invisibleAnnotations);
            if (sh == null) continue;
            String name = m.name.startsWith("shadow$") ? m.name.substring(7) : m.name;
            if (find(cur_class, name, m.desc) != null) continue;
            sh.desc = UNIQUE;
            sh.values = null;

            if ((m.access & Opcodes.ACC_STATIC) != 0) m.access = m.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
            boolean stat = (m.access & Opcodes.ACC_STATIC) != 0;
            m.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
            m.instructions = new InsnList();
            m.tryCatchBlocks = new ArrayList<>();
            int slot = 0;
            if (!stat) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot++));
            for (Type t : Type.getArgumentTypes(m.desc)) { m.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot)); slot += t.getSize(); }
            MethodInsnNode x = new MethodInsnNode(stat ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, cur_class, name, m.desc, false);
            m.instructions.add(x);
            m.instructions.add(new InsnNode(Type.getReturnType(m.desc).getOpcode(Opcodes.IRETURN)));
            m.maxLocals = slot;
            m.maxStack = slot + 4;
            call(m, x, c.name);
            rewired++;
        }
    }

    boolean flip_static(ClassNode c, FieldNode f, boolean stat) {
        FieldNode t = null;
        for (String o = cur_class; o != null && g7.containsKey(o) && t == null; o = g7.get(o).superName)
            for (FieldNode k : g7.get(o).fields) if (k.name.equals(f.name) && k.desc.equals(f.desc)) t = k;
        if (t == null || ((t.access & Opcodes.ACC_STATIC) != 0) == stat || Type.getType(f.desc).getSize() != 1) return false;
        List<Object[]> uses = new ArrayList<>();
        for (MethodNode m : c.methods) if (m.instructions != null) for (AbstractInsnNode in : m.instructions.toArray()) {
            if (!(in instanceof FieldInsnNode)) continue;
            FieldInsnNode x = (FieldInsnNode) in;
            if (!x.owner.equals(c.name) || !x.name.equals(f.name) || !x.desc.equals(f.desc)) continue;
            if (stat && (m.access & Opcodes.ACC_STATIC) != 0) return false;
            uses.add(new Object[] { m, x });
        }
        for (Object[] u : uses) {
            MethodNode m = (MethodNode) u[0];
            FieldInsnNode x = (FieldInsnNode) u[1];
            int op = x.getOpcode();
            if (stat) {
                m.instructions.insertBefore(x, new VarInsnNode(Opcodes.ALOAD, 0));
                if (op == Opcodes.PUTSTATIC) m.instructions.insertBefore(x, new InsnNode(Opcodes.SWAP));
                x.setOpcode(op == Opcodes.GETSTATIC ? Opcodes.GETFIELD : Opcodes.PUTFIELD);
                m.maxStack += 2;
            } else {
                if (op == Opcodes.PUTFIELD) m.instructions.insertBefore(x, new InsnNode(Opcodes.SWAP));
                m.instructions.insertBefore(x, new InsnNode(Opcodes.POP));
                x.setOpcode(op == Opcodes.GETFIELD ? Opcodes.GETSTATIC : Opcodes.PUTSTATIC);
            }
        }
        f.access ^= Opcodes.ACC_STATIC;
        return true;
    }

    static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    void overwrites(ClassNode c) {
        if ((c.access & Opcodes.ACC_INTERFACE) != 0 || cur_class == null || !g7.containsKey(cur_class)) return;
        for (MethodNode m : c.methods) {
            if (m.name.startsWith("<")) continue;
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l) {
                if (!a.desc.equals(OVERWRITE) || find(cur_class, m.name, m.desc) != null) continue;
                a.desc = UNIQUE; a.values = null;
                if ((m.access & Opcodes.ACC_STATIC) != 0) m.access = m.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
                stubbed++;
            }
        }
    }

    boolean retyped_shadow(ClassNode c, FieldNode f, boolean stat) {
        FieldNode real = null;
        for (String o = cur_class; o != null && g7.containsKey(o) && real == null; o = g7.get(o).superName)
            for (FieldNode t : g7.get(o).fields) if (t.name.equals(f.name) && ((t.access & Opcodes.ACC_STATIC) != 0) == stat) real = t;
        if (real == null) return false;
        String[] cv = converter(real.desc, f.desc);
        if (cv == null) return false;
        int put = stat ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD, get = stat ? Opcodes.GETSTATIC : Opcodes.GETFIELD;
        List<FieldInsnNode> reads = new ArrayList<>();
        for (MethodNode m : c.methods) if (m.instructions != null) for (AbstractInsnNode in : m.instructions.toArray()) {
            if (!(in instanceof FieldInsnNode)) continue;
            FieldInsnNode x = (FieldInsnNode) in;
            if (!x.owner.equals(c.name) || !x.name.equals(f.name) || !x.desc.equals(f.desc)) continue;
            if (x.getOpcode() == put) return false;
            if (x.getOpcode() == get) reads.add(x);
        }
        for (MethodNode m : c.methods) if (m.instructions != null) for (FieldInsnNode x : reads) if (m.instructions.contains(x)) {
            m.instructions.insert(x, new MethodInsnNode(Opcodes.INVOKESTATIC, cv[0], cv[1], cv[2], false));
            x.desc = real.desc;
        }
        f.desc = real.desc;
        f.signature = null;
        return true;
    }

    static final Set<String> CALL_HOOKS = new HashSet<>(Arrays.asList("Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));

    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;", WRAP_METHOD = "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";

    boolean method_fits(AnnotationNode a, MethodNode h) {
        ClassNode t = cur_class == null ? null : g7.get(cur_class);
        if (t == null || a.values == null) return true;
        List<String> sels = new ArrayList<>();
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals("method") && a.values.get(i + 1) instanceof List) for (Object o : (List<?>) a.values.get(i + 1)) sels.add((String) o);
        if (sels.isEmpty()) return true;
        Type[] ha = Type.getArgumentTypes(h.desc);
        for (String s : sels) {
            String nm = s.contains("(") ? s.substring(0, s.indexOf('(')) : s, ds = s.contains("(") ? s.substring(s.indexOf('(')) : null;
            if (nm.contains("*") || nm.startsWith("<clinit>")) return true;
            for (MethodNode tm : t.methods) {
                if (!tm.name.equals(nm) || ds != null && !tm.desc.equals(ds)) continue;
                Type[] ta = Type.getArgumentTypes(tm.desc);
                if (ha.length < ta.length) continue;
                boolean ok = true;
                for (int i = 0; i < ta.length && ok; i++) ok = ha[i].equals(ta[i]);
                if (!ok) continue;
                if (a.desc.equals(WRAP_METHOD) && !Type.getReturnType(h.desc).equals(Type.getReturnType(tm.desc))) continue;
                if (a.desc.equals(INJECT) && ha.length > ta.length && !ha[ta.length].getDescriptor().startsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo")) continue;

                if (a.desc.equals(INJECT) && !returns_fit(h, Type.getReturnType(tm.desc))) continue;
                return true;
            }
        }
        return false;
    }

    boolean returns_fit(MethodNode h, Type ret) {
        if (h.signature == null) return true;
        int at = h.signature.indexOf("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<");
        if (at < 0) return true;
        String rest = h.signature.substring(at + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<".length());
        if (!rest.startsWith("L")) return true;
        int end = rest.indexOf(';'), lt = rest.indexOf('<');
        String t = rest.substring(1, lt >= 0 && lt < end ? lt : end);
        if (ret.getSort() == Type.VOID) return true;
        if (ret.getSort() != Type.OBJECT && ret.getSort() != Type.ARRAY) return t.equals(boxed(ret));
        return ret.getSort() == Type.ARRAY || t.equals(ret.getInternalName()) || t.equals("java/lang/Object") || assignable(t, ret.getInternalName());
    }

    static String boxed(Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN: return "java/lang/Boolean"; case Type.BYTE: return "java/lang/Byte"; case Type.CHAR: return "java/lang/Character";
            case Type.SHORT: return "java/lang/Short"; case Type.INT: return "java/lang/Integer"; case Type.LONG: return "java/lang/Long";
            case Type.FLOAT: return "java/lang/Float"; default: return "java/lang/Double";
        }
    }

    void call_hooks(ClassNode c) {
        for (MethodNode m : c.methods) for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
            if (l == null) continue;
            for (AnnotationNode a : l) {
                boolean whole = a.desc.equals(INJECT) || a.desc.equals(WRAP_METHOD);
                if (!CALL_HOOKS.contains(a.desc) && !whole || a.values == null) continue;
                boolean ok = true;
                if (whole) ok = method_fits(a, m);
                else for (String tg : at_targets(a)) ok &= call_fits(tg, m, a.desc.contains("WrapOperation"));
                if (ok) continue;
                a.desc = UNIQUE;
                a.values = null;

                m.instructions = new InsnList();
                m.tryCatchBlocks = new ArrayList<>();
                m.localVariables = null;
                dflt(m, Type.getReturnType(m.desc));
                m.maxStack = 2;
                m.maxLocals = Type.getArgumentsAndReturnSizes(m.desc) >> 2;
                DEAD_HOOKS.add(c.name + "." + m.name + ": the call it stands in for isn't the same on 7.3");
                stubbed++;
            }
        }
    }

    static List<String> at_targets(AnnotationNode a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.values.size(); i += 2) {
            Object v = a.values.get(i + 1);
            List<?> ats = v instanceof AnnotationNode ? Collections.singletonList(v) : v instanceof List ? (List<?>) v : Collections.emptyList();
            if (!a.values.get(i).equals("at")) continue;
            for (Object o : ats) if (o instanceof AnnotationNode && ((AnnotationNode) o).values != null) {
                List<Object> av = ((AnnotationNode) o).values;
                for (int k = 0; k < av.size(); k += 2) if (av.get(k).equals("target") && av.get(k + 1) instanceof String) out.add((String) av.get(k + 1));
            }
        }
        return out;
    }

    boolean call_fits(String tg, MethodNode h, boolean wrap) {
        if (!tg.startsWith("L") || tg.indexOf(';') < 0 || tg.indexOf('(') < 0) return true;
        String owner = tg.substring(1, tg.indexOf(';')), name = tg.substring(tg.indexOf(';') + 1, tg.indexOf('(')), desc = tg.substring(tg.indexOf('('));
        if (!game(owner)) return true;
        MethodNode t = find(owner, name, desc);
        if (t == null || t == OBJ_ANY) return false;
        List<Type> want = new ArrayList<>();
        if ((t.access & Opcodes.ACC_STATIC) == 0) want.add(Type.getObjectType(owner));
        want.addAll(Arrays.asList(Type.getArgumentTypes(desc)));
        Type[] got = Type.getArgumentTypes(h.desc);
        if (got.length < want.size()) return false;
        for (int i = 0; i < want.size(); i++) if (!got[i].equals(want.get(i)) && !(i == 0 && got[i].getSort() == Type.OBJECT && want.get(i).getSort() == Type.OBJECT && assignable(want.get(i).getInternalName(), got[i].getInternalName()))) return false;
        return Type.getReturnType(h.desc).equals(Type.getReturnType(desc)) || !wrap && Type.getReturnType(desc).getSort() == Type.VOID;
    }

    @SafeVarargs
    static AnnotationNode shadow_of(List<AnnotationNode>... ls) {
        for (List<AnnotationNode> l : ls) if (l != null) for (AnnotationNode a : l) if (a.desc.equals(SHADOW)) return a;
        return null;
    }

    static boolean has_own_field(ClassNode t, String name, String desc) {
        for (FieldNode f : t.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
        return false;
    }

    static String accessor_field(String m) {
        for (String p : new String[] { "get", "set", "is" })
            if (m.startsWith(p) && m.length() > p.length()) return Character.toLowerCase(m.charAt(p.length())) + m.substring(p.length() + 1);
        return m;
    }

    static String accessor_method(String m) {
        for (String p : new String[] { "invoke", "call" })
            if (m.startsWith(p) && m.length() > p.length()) return Character.toLowerCase(m.charAt(p.length())) + m.substring(p.length() + 1);
        return m;
    }

    static void set(AnnotationNode a, String k, Object v) {
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(k)) { a.values.set(i + 1, v); return; }
        a.values.add(k);
        a.values.add(v);
    }

    static final Map<String, Integer> NEED = new TreeMap<>();

    static String parent(String stub) {
        if (G8.isEmpty()) load_g8_table();
        ClassNode c = G8.get(stub);
        Set<String> seen = new HashSet<>();
        while (c != null && c.superName != null && seen.add(c.name)) {
            String s = c.superName, m = moved(s);
            if (STUBS.containsKey(s)) return s;

            if (SHIM != null && SHIM.containsKey(s) && (SHIM.get(s).access & (Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE)) == 0) return s;
            ClassNode k = G7 == null ? null : G7.get(m);

            if (k != null && (k.access & Opcodes.ACC_FINAL) == 0) {

                if ((k.access & Opcodes.ACC_ABSTRACT) != 0) {
                    ClassNode std = G7.get(m + "Standard");
                    if (std != null && m.equals(std.superName) && (std.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_FINAL)) == 0) return std.name;
                }
                for (MethodNode mn : k.methods) if (mn.name.equals("<init>") && (mn.access & Opcodes.ACC_PRIVATE) == 0) return m;
            }
            c = G8.get(s);
        }
        return "java/lang/Object";
    }

    static void load_g8_table() {
        try (InputStream in = Vt.class.getResourceAsStream("/openhouse/v7/g8tree.txt")) {
            if (in == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) {
                String[] t = l.split(" ");
                if (t.length < 2) continue;
                ClassNode c = new ClassNode();
                c.name = t[0];
                c.superName = t[1];
                for (int i = 2; i < t.length; i++) {
                    if (t[i].equals("I")) c.access |= Opcodes.ACC_INTERFACE;
                    else if (t[i].equals("F")) c.access |= Opcodes.ACC_FINAL;
                    else if (t[i].startsWith("+")) c.interfaces.add(t[i].substring(1));
                    else if (t[i].startsWith("E=")) {
                        c.access |= Opcodes.ACC_ENUM;
                        for (String n : t[i].substring(2).split(",")) if (!n.isEmpty()) c.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ENUM, n, "L" + c.name + ";", null, null));
                    }
                }
                G8.put(c.name, c);
            }
        } catch (IOException ignored) { }
    }

    static void close_stubs() {
        if (G8.isEmpty()) load_g8_table();
        for (boolean grew = true; grew; ) {
            grew = false;
            for (String n : new ArrayList<>(STUBS.keySet())) {
                ClassNode c = G8.get(n);
                if (c == null) continue;
                if ((c.access & Opcodes.ACC_INTERFACE) != 0) STUBS.get(n).itf = true;
                List<String> up = new ArrayList<>(c.interfaces);
                if (c.superName != null) up.add(c.superName);
                for (String u : up) {
                    if (!game(u) || STUBS.containsKey(u) || G7 != null && G7.containsKey(moved(u)) || SHIM != null && SHIM.containsKey(u)) continue;
                    stub(u);
                    grew = true;
                }
            }
        }

        for (boolean grew = true; grew; ) {
            grew = false;
            for (Map.Entry<String, Stub> e : new ArrayList<>(STUBS.entrySet())) {
                if (e.getValue().itf) continue;
                String p = parent(e.getKey());
                Stub ps = STUBS.get(p);
                if (ps == null || ps.itf) continue;
                for (String k : new ArrayList<>(e.getValue().methods.keySet()))
                    if (k.startsWith("<init>") && ps.methods.putIfAbsent(k, Opcodes.INVOKESPECIAL) == null) grew = true;
            }
        }
    }

    static void super_init_call(MethodNode mn, String sup, String desc) {
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ClassNode k = G7 == null || STUBS.containsKey(sup) ? null : G7.get(sup);
        if (k == null && SHIM != null && !STUBS.containsKey(sup)) k = SHIM.get(sup);

        String use = STUBS.containsKey(sup) ? desc : "()V";
        if (k != null) {

            MethodNode same = null, none = null, any = null, full = null;
            int full_n = -1;
            Type[] ours = Type.getArgumentTypes(desc);
            for (MethodNode m : k.methods) {

                if (!m.name.equals("<init>") || (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) continue;
                if (m.desc.equals(desc)) same = m;
                if (m.desc.equals("()V")) none = m;
                if (any == null) any = m;
                Type[] p = Type.getArgumentTypes(m.desc);
                boolean[] u = new boolean[ours.length];
                int n = 0;
                for (Type t : p) for (int i = 0; i < ours.length; i++) if (!u[i] && ours[i].equals(t)) { u[i] = true; n++; break; }
                if (n == p.length && n > full_n) { full = m; full_n = n; }
            }
            MethodNode pick = same != null ? same : full != null && full_n > 0 ? full : none != null ? none : any;
            if (pick != null) use = pick.desc;
        }
        Type[] mine = Type.getArgumentTypes(desc);
        int[] at = new int[mine.length];
        for (int i = 0, slot = 1; i < mine.length; i++) { at[i] = slot; slot += mine[i].getSize(); }

        boolean[] used = new boolean[mine.length];
        for (Type t : Type.getArgumentTypes(use)) {
            int pick = -1;
            for (int i = 0; i < mine.length && pick < 0; i++) if (!used[i] && mine[i].equals(t)) pick = i;

            if (pick < 0 && t.getDescriptor().equals("Ljava/lang/Object;")) for (int i = 0; i < mine.length && pick < 0; i++) if (!used[i] && mine[i].getSort() >= Type.ARRAY) pick = i;
            if (pick >= 0) { used[pick] = true; mn.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), at[pick])); }
            else if (t.getSort() == Type.ARRAY) empty_array(mn.instructions, t);
            else mn.instructions.add(dflt_value(t));
        }
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, sup, "<init>", use, false));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    static AbstractInsnNode dflt_value(Type t) {
        switch (t.getSort()) {
            case Type.LONG: return new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT: return new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE: return new InsnNode(Opcodes.DCONST_0);
            case Type.OBJECT: case Type.ARRAY: return new InsnNode(Opcodes.ACONST_NULL);
            default: return new InsnNode(Opcodes.ICONST_0);
        }
    }

    static List<String> enum_names(ClassNode g8) {
        if ((g8.access & Opcodes.ACC_ENUM) == 0) return null;
        List<String> l = new ArrayList<>();
        for (FieldNode f : g8.fields) if ((f.access & Opcodes.ACC_ENUM) != 0) l.add(f.name);
        return l;
    }

    static final Set<String> ENUM_FINALS = new HashSet<>(Arrays.asList("ordinal()I", "name()Ljava/lang/String;", "toString()Ljava/lang/String;",
            "equals(Ljava/lang/Object;)Z", "hashCode()I", "compareTo(Ljava/lang/Enum;)I", "getDeclaringClass()Ljava/lang/Class;", "clone()Ljava/lang/Object;", "finalize()V"));
    static void enum_parts(ClassNode c, List<String> en, Stub st) {
        String t = "L" + c.name + ";", arr = "[" + t;
        c.methods.removeIf(m -> m.name.equals("<clinit>") || ENUM_FINALS.contains(m.name + m.desc)
                || m.desc.equals("()" + arr) && m.name.equals("values") || m.desc.equals("(Ljava/lang/String;)" + t) && m.name.equals("valueOf"));
        c.superName = "java/lang/Enum";
        c.access |= Opcodes.ACC_ENUM;

        for (MethodNode k : c.methods) {
            if (!k.name.equals("<init>")) continue;
            k.instructions = new InsnList();
            k.tryCatchBlocks = new ArrayList<>();
            k.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            k.instructions.add(new LdcInsnNode("?"));
            k.instructions.add(new InsnNode(Opcodes.ICONST_M1));
            k.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false));
            k.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        MethodNode mk = new MethodNode(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;ILjava/lang/Void;)V", null, null);
        mk.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mk.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mk.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        mk.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false));
        mk.instructions.add(new InsnNode(Opcodes.RETURN));
        c.methods.add(mk);
        for (String n : en) st.fields.put(n + " " + t, true);
        c.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "oh$all", arr, null, null));
        MethodNode ci = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        InsnList il = ci.instructions;
        for (int i = 0; i < en.size(); i++) {
            il.add(new TypeInsnNode(Opcodes.NEW, c.name));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new LdcInsnNode(en.get(i)));
            il.add(new LdcInsnNode(i));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, c.name, "<init>", "(Ljava/lang/String;ILjava/lang/Void;)V", false));
            il.add(new FieldInsnNode(Opcodes.PUTSTATIC, c.name, en.get(i), t));
        }
        il.add(new LdcInsnNode(en.size()));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, c.name));
        for (int i = 0; i < en.size(); i++) {
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new LdcInsnNode(i));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, c.name, en.get(i), t));
            il.add(new InsnNode(Opcodes.AASTORE));
        }
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, c.name, "oh$all", arr));
        il.add(new InsnNode(Opcodes.RETURN));
        c.methods.add(ci);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()" + arr, null, null);
        m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, c.name, "oh$all", arr));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, arr, "clone", "()Ljava/lang/Object;", false));
        m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, arr));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        c.methods.add(m);
        m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)" + t, null, null);
        m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, c.name, "oh$all", arr));
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Enums", "of", "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, c.name));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        c.methods.add(m);
    }

    static void const_parts(ClassNode c, Stub st) {
        InsnList il = new InsnList();
        for (Map.Entry<String, Boolean> f : st.fields.entrySet()) {
            if (!f.getValue()) continue;
            String[] nd = f.getKey().split(" ");
            String how = nd[1].equals("Lnet/minecraft/core/data/tag/Tag;") ? "tag" : nd[1].equals("Lnet/minecraft/core/item/Item;") ? "item" : null;

            String fresh = how != null || !nd[1].startsWith("L") ? null : EMPTY_OF.containsKey(nd[1]) ? EMPTY_OF.get(nd[1]) : plain_new(nd[1].substring(1, nd[1].length() - 1));
            if (how == null && fresh == null) continue;
            if (how != null) {
                il.add(new LdcInsnNode(c.name.substring(c.name.lastIndexOf('/') + 1) + "." + nd[0]));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "openhouse/v7/Missing", how, "(Ljava/lang/String;)" + nd[1], false));
            } else {
                il.add(new TypeInsnNode(Opcodes.NEW, fresh));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, fresh, "<init>", "()V", false));
            }
            il.add(new FieldInsnNode(Opcodes.PUTSTATIC, c.name, nd[0], nd[1]));
        }
        if (il.size() == 0) return;
        MethodNode ci = null;
        for (MethodNode m : c.methods) if (m.name.equals("<clinit>")) ci = m;
        if (ci == null) {
            ci = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            ci.instructions.add(new InsnNode(Opcodes.RETURN));
            c.methods.add(ci);
        }
        ci.instructions.insertBefore(ci.instructions.getLast(), il);
    }

    static boolean stub_self(String name, String sup, Type r) {
        if (r.getSort() != Type.OBJECT) return false;
        String t = r.getInternalName();
        if (t.equals(name)) return true;
        for (String o = sup; o != null && !o.equals("java/lang/Object"); ) {
            if (o.equals(t)) return true;
            if (G7 != null && G7.containsKey(o)) o = G7.get(o).superName;
            else if (G8.containsKey(o)) o = G8.get(o).superName;
            else break;
        }
        return false;
    }

    static String plain_new(String n) {
        ClassNode k = G7 == null ? null : G7.get(n);
        if (k == null || (k.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) return null;
        for (MethodNode m : k.methods) if (m.name.equals("<init>") && m.desc.equals("()V") && (m.access & Opcodes.ACC_PUBLIC) != 0) return n;
        return null;
    }

    static void write_stubs(String out) throws IOException {
        close_stubs();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, Stub> e : STUBS.entrySet()) {
                Stub st = e.getValue();
                ClassNode c = new ClassNode();
                c.version = Opcodes.V17;
                c.name = e.getKey();

                String sup = st.itf ? "java/lang/Object" : parent(e.getKey());
                c.superName = sup;
                c.access = Opcodes.ACC_PUBLIC | (st.itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
                ClassNode g8 = G8.get(e.getKey());
                if (g8 != null) for (String i : g8.interfaces) {
                    if (STUBS.containsKey(i)) { if (STUBS.get(i).itf) c.interfaces.add(i); continue; }
                    ClassNode k = G7 == null ? null : G7.get(moved(i));
                    if (k != null && (k.access & Opcodes.ACC_INTERFACE) != 0) c.interfaces.add(moved(i));
                }
                boolean has_init = false;
                for (Map.Entry<String, Integer> m : st.methods.entrySet()) {
                    if (st.abstracts.contains(m.getKey())) continue;
                    String name = m.getKey().substring(0, m.getKey().indexOf('(')), desc = m.getKey().substring(name.length());
                    boolean stat = m.getValue() == Opcodes.INVOKESTATIC;
                    if (name.equals("<init>")) {
                        if (st.itf) continue;
                        has_init |= desc.equals("()V");
                        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
                        super_init_call(mn, sup, desc);
                        c.methods.add(mn);
                        continue;
                    }
                    MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | (stat ? Opcodes.ACC_STATIC : 0), name, desc, null, null);
                    miss(mn, e.getKey() + "." + name);
                    if (!stat && stub_self(c.name, sup, Type.getReturnType(desc))) {
                        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
                    } else dflt(mn, Type.getReturnType(desc));
                    c.methods.add(mn);
                }
                for (String sam : st.abstracts) {
                    String nm = sam.substring(0, sam.indexOf('('));
                    c.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, nm, sam.substring(nm.length()), null, null));
                }
                if (!st.itf && !has_init) {
                    MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    super_init_call(mn, sup, "()V");
                    c.methods.add(mn);
                }
                List<String> en = g8 == null || st.itf ? null : enum_names(g8);
                if (en != null && !en.isEmpty()) enum_parts(c, en, st);
                if (!st.itf) const_parts(c, st);

                if (!st.itf && G7 != null) {
                    Set<String> have = new HashSet<>();
                    for (MethodNode mn : c.methods) have.add(mn.name + mn.desc);

                    java.util.function.Function<String, ClassNode> node = o -> G7.containsKey(o) ? G7.get(o) : SHIM != null ? SHIM.get(o) : null;
                    List<String> up = new ArrayList<>();
                    for (String o = sup; o != null && node.apply(o) != null; o = node.apply(o).superName) up.add(o);
                    for (String o : up) for (MethodNode mn : node.apply(o).methods) if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) have.add(mn.name + mn.desc);
                    for (String o : up) for (MethodNode am : node.apply(o).methods) {
                        if ((am.access & Opcodes.ACC_ABSTRACT) == 0 || !have.add(am.name + am.desc)) continue;
                        MethodNode d = new MethodNode(Opcodes.ACC_PUBLIC, am.name, am.desc, null, null);
                        miss(d, e.getKey() + "." + am.name);
                        dflt(d, Type.getReturnType(am.desc));
                        c.methods.add(d);
                    }
                }
                for (Map.Entry<String, Boolean> f : st.fields.entrySet()) {
                    String[] nd = f.getKey().split(" ");
                    c.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | (f.getValue() || st.itf ? Opcodes.ACC_STATIC : 0), nd[0], nd[1], null, null));
                }
                ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                c.accept(w);
                z.putNextEntry(new ZipEntry(c.name + ".class"));
                z.write(w.toByteArray());
                z.closeEntry();
            }
        }
    }

    public static void main(String[] a) throws Exception {
        Map<String, ClassNode> g7 = new HashMap<>();
        for (String j : a[0].split(";")) g7.putAll(load(j));
        Map<String, ClassNode> sh = load(a[1]);
        for (int i = 2; i + 1 < a.length; i += 2) {
            String id = new File(a[i]).getName().replaceAll("[-+].*", "");
            Vt v = new Vt(g7, sh, id);
            v.translate(a[i], a[i + 1]);
            int un = 0;
            for (int n : v.unresolved.values()) un += n;
            System.out.println("== " + new File(a[i]).getName() + ": " + v.adapted + " adapted, " + v.rewired + " to shims, " + v.stubbed + " stubbed, "
                    + v.unresolved.size() + " unresolved (" + un + " uses)");
            if (System.getProperty("v") != null) v.unresolved.forEach((k, n) -> System.out.println("  " + k + " x" + n));
            for (Vt sub : v.nested) {
                int su = 0;
                for (int n : sub.unresolved.values()) su += n;
                System.out.println("   + " + sub.gen_class.replaceAll(".*A_", "") + " (bundled): " + sub.adapted + " adapted, " + sub.unresolved.size() + " unresolved (" + su + " uses)");
            }
        }
        if (System.getProperty("stubs") != null) { write_stubs(System.getProperty("stubs")); System.out.println("stub classes: " + STUBS.size()); }
        if (System.getProperty("need") != null) NEED.forEach((k, n) -> System.out.println("NEED " + k + " x" + n));
        if (System.getProperty("hooks") != null) for (String h : DEAD_HOOKS) System.out.println("DEAD " + h);
    }
}
