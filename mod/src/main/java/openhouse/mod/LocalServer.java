package openhouse.mod;

import openhouse.Rtc;
import openhouse.mod.server.ServerRoom;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.option.GameSettings;
import net.minecraft.core.enums.Difficulty;

public final class LocalServer {
    public enum State { STARTING, READY, STOPPING, STOPPED, FAILED }

    private static volatile LocalServer current;

    public final String worldDir;
    public volatile State state = State.STARTING;
    public volatile String detail = "";
    public volatile int port;
    public volatile String code;

    public volatile List<String> leftOut = new ArrayList<>();
    private volatile boolean dying;
    volatile boolean announced;

    public volatile String crash;
    public volatile String crashMod;
    volatile boolean crashShown;
    private volatile List<ServerMods.Entry> serverMods = new ArrayList<>();

    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private int forwarded;
    private boolean inError;
    private volatile Process proc;
    private volatile OutputStream stdin;
    private final boolean online;
    private final String username;
    private final java.util.UUID uuid;

    final String hostToken = newHostToken();

    private LocalServer(String worldDir, String username, java.util.UUID uuid, boolean online) {
        this.worldDir = worldDir;
        this.username = username;
        this.uuid = uuid;
        this.online = online;
    }

    public static LocalServer current() { return current; }

    public static String hostToken(String host, int port) {
        LocalServer s = current;
        if (s == null || s.port <= 0 || port != s.port || !hosting()) return null;
        String h = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT);
        boolean loopback = h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || h.equals("[::1]");
        return loopback ? s.hostToken : null;
    }

    private static String newHostToken() {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder(ServerRoom.HOST_TOKEN_LENGTH);
        for (int i = 0; i < ServerRoom.HOST_TOKEN_LENGTH; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    public static boolean hosting() {
        LocalServer s = current;
        return s != null && (s.state == State.STARTING || s.state == State.READY);
    }

    public static boolean supported() {
        return Rtc.BTA_SERVER_VERSION.equals(gameVersion());
    }

    public static String gameVersion() {
        try {
            return String.valueOf(Class.forName("net.minecraft.core.Version").getField("VERSION").get(null));
        } catch (Throwable t) {
            return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        }
    }

    public static synchronized LocalServer start(Minecraft mc, String worldDir) {
        if (hosting()) return current;
        String sid = mc.session.sessionId;
        boolean online = sid != null && !sid.isEmpty() && !sid.equals("-");
        LocalServer s = new LocalServer(worldDir, mc.session.username, mc.session.uuid, online);
        current = s;
        OpenHouseDiscord.shareCode = false;
        Thread t = new Thread(s::run, "openhouse local server");
        t.setDaemon(true);
        t.start();
        return s;
    }

    private void set(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
    }

    private final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);

    public void worldClosed() {
        closed.countDown();
    }

    private static volatile Thread prefetch;

    static synchronized void prefetch() {
        if (prefetch != null || !supported()) return;
        File lib = new File(OpenHouseMod.dataDir(), "lib");
        Thread t = new Thread(() -> {
            java.util.function.Consumer<String> status = s -> {
                LocalServer c = current;
                if (c != null && c.state == State.STARTING) c.detail = s;
            };
            try {
                Rtc.btaServer(lib, status);
                Rtc.libraries(lib, status);
            } catch (Exception e) {

                OpenHouseMod.LOG.info("couldn't fetch the server ahead of time: {}", e.getMessage());
            }
        }, "openhouse prefetch");
        t.setDaemon(true);
        prefetch = t;
        t.start();
    }

    private static void awaitPrefetch() {
        Thread t = prefetch;
        if (t == null) return;
        try { t.join(); } catch (InterruptedException ignored) { }
    }

    private void run() {
        File base = OpenHouseMod.dataDir();
        File dir = new File(base, "server");
        try {
            set(State.STARTING, "Getting the server ready...");
            awaitPrefetch();
            File serverJar = Rtc.btaServer(new File(base, "lib"), s -> detail = s);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
            File world = new File(FabricLoader.getInstance().getGameDir().toFile(), "saves" + File.separator + worldDir);
            if (!new File(world, "level.dat").isFile()) throw new IOException("can't find the world's save (" + world + ")");
            List<ServerMods.Entry> mods = ServerMods.loaded();

            File gate = new File(dir, "world-closed.flag");
            File share = new File(base, "share-game.json");
            if ((gate.exists() && !gate.delete()) || (share.exists() && !share.delete()))
                throw new IOException("can't clear the last opening's files in " + base);
            handOver(mods, base, world, gate);

            copyConfigs(new File(FabricLoader.getInstance().getGameDir().toFile(), "config"), new File(dir, "config"));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stopAndWait(30000), "openhouse local server stop"));

            for (int attempt = 1; ; attempt++) {
                List<ServerMods.Entry> left = new ArrayList<>();
                List<ServerMods.Entry> in = ServerMods.forServer(mods, ServerMods.remembered(), left);
                List<String> leftNames = new ArrayList<>();
                Set<String> leftIds = new LinkedHashSet<>();
                for (ServerMods.Entry e : left) { leftNames.add(e.name); leftIds.add(e.id); }
                leftOut = leftNames;
                serverMods = in;
                List<String> lines = runServer(serverJar, dir, world, ServerMods.sync(in), share, gate);
                if (lines == null) return;

                try {
                    java.nio.file.Files.copy(new File(dir, "console.log").toPath(), new File(dir, "console-failed-" + attempt + ".log").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
                Set<String> bad = ServerMods.culprits(lines, in, leftIds);
                if (bad.isEmpty() || attempt >= 15) {
                    OpenHouseMod.LOG.warn("Open to Friends: the server for {} couldn't start (its errors are above) - everything it printed is in {}", worldDir, new File(dir, "console.log"));
                    set(State.FAILED, "The server couldn't start. See openhouse/server/console.log.");
                    return;
                }
                Map<String, String> skip = new LinkedHashMap<>();
                List<String> names = new ArrayList<>();
                for (ServerMods.Entry e : in) if (bad.contains(e.id)) { skip.put(e.id, e.version); names.add(e.name); }
                ServerMods.remember(skip);
                OpenHouseMod.LOG.info("leaving {} out of the Open to Friends server - it stopped the server from starting (it still runs in the game)", names);
                set(State.STARTING, "Leaving out " + String.join(", ", names) + " (can't run on a server) - starting again...");
            }
        } catch (Exception e) {
            OpenHouseMod.LOG.warn("Open to Friends failed", e);
            if (state != State.STOPPING) set(State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
            else set(State.STOPPED, "");
        }
    }

    private void handOver(List<ServerMods.Entry> mods, File base, File world, File gate) {
        Thread t = new Thread(() -> {
            try {
                closed.await();
            } catch (InterruptedException e) {
                return;
            }
            shareManifest(mods, base, world);
            try {
                java.nio.file.Files.write(gate.toPath(), new byte[0]);
            } catch (IOException e) {
                OpenHouseMod.LOG.warn("Open to Friends: can't tell the server the world is saved ({})", e.toString());
                set(State.FAILED, "Couldn't hand the world to the server: " + e.getMessage());
                Process p = proc;
                if (p != null) p.destroyForcibly();
            }
        }, "openhouse world hand-over");
        t.setDaemon(true);
        t.start();
    }

    static final java.util.regex.Pattern PRIVATE = java.util.regex.Pattern.compile("(?i)token|secret|passw|auth|account|credential|webhook|api.?key|session");

    static File zipConfigs(File configDir, File zip) {
        if (!configDir.isDirectory()) return null;
        int n = 0;
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            List<File> todo = new ArrayList<>();
            todo.add(configDir);
            while (!todo.isEmpty()) {
                File d = todo.remove(todo.size() - 1);
                File[] kids = d.listFiles();
                if (kids == null) continue;
                java.util.Arrays.sort(kids);
                for (File k : kids) {
                    if (PRIVATE.matcher(k.getName()).find()) continue;
                    if (k.isDirectory()) { todo.add(k); continue; }
                    if (k.length() > 512 * 1024) continue;
                    String name = configDir.toPath().relativize(k.toPath()).toString().replace('\\', '/');
                    java.util.zip.ZipEntry e = new java.util.zip.ZipEntry(name);
                    e.setTime(0);
                    z.putNextEntry(e);
                    java.nio.file.Files.copy(k.toPath(), z);
                    z.closeEntry();
                    n++;
                }
            }
        } catch (IOException e) {
            OpenHouseMod.LOG.warn("couldn't pack the configs to share: {}", e.toString());
            return null;
        }
        return n > 0 ? zip : null;
    }

    static void copyConfigs(File from, File to) {
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File t = new File(to, k.getName());
            if (k.isDirectory()) { copyConfigs(k, t); continue; }
            if (t.isFile() && t.length() == k.length() && t.lastModified() == k.lastModified()) continue;
            try {
                to.mkdirs();
                java.nio.file.Files.copy(k.toPath(), t.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException e) {
                OpenHouseMod.LOG.warn("couldn't copy config {} for the server: {}", k, e.toString());
            }
        }
    }

    private static File shareManifest(List<ServerMods.Entry> mods, File base, File world) {
        try {
            List<File> jars = new ArrayList<>();
            for (ServerMods.Entry e : mods) jars.add(e.jar);
            List<openhouse.ModShare.Mod> share = openhouse.ModShare.scanFiles(jars);
            File cfg = zipConfigs(new File(FabricLoader.getInstance().getGameDir().toFile(), "config"), new File(base, "share-config.zip"));
            if (cfg != null) share.add(openhouse.ModShare.extra(cfg, "openhouse:config", "the host's mod settings"));
            File table = new File(world, "uselessNumericalSave.dat");
            if (FabricLoader.getInstance().isModLoaded("uselessnumerical") && table.isFile()) {
                File copy = new File(base, "share-uselessnumerical.dat");
                java.nio.file.Files.copy(table.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                share.add(openhouse.ModShare.extra(copy, "openhouse:uselessnumerical", "the world's id table"));
            }
            return openhouse.ModShare.writeHostManifest(share, new File(base, "share-game.json"));
        } catch (Exception e) {
            OpenHouseMod.LOG.warn("can't list the game's mods to share: {}", e.toString());
            return null;
        }
    }

    private List<String> runServer(File serverJar, File dir, File world, File modsDir, File share, File gate) throws Exception {
        {
            port = freePort();
            writeSettings(dir);
            List<String> cmd = new ArrayList<>();
            cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath());
            cmd.add("-Xmx" + serverHeapMb() + "M");

            cmd.add("-Djava.awt.headless=true");
            cmd.add("-Dfabric.modsFolder=" + modsDir.getAbsolutePath());

            cmd.add("-Dopenhouse.shareManifest=" + share.getAbsolutePath());
            cmd.add("-Dopenhouse.worldGate=" + gate.getAbsolutePath());
            cmd.add("-Dopenhouse.lan=1");
            cmd.add("-Dopenhouse.dataDir=" + OpenHouseMod.dataDir().getAbsolutePath());
            cmd.add("-Dopenhouse.parentPid=" + ProcessHandle.current().pid());
            cmd.add("-cp");
            StringBuilder cp = new StringBuilder();
            for (File f : loaderClasspath()) cp.append(f.getAbsolutePath()).append(File.pathSeparator);
            cp.append(serverJar.getAbsolutePath());
            cmd.add(cp.toString());
            cmd.add("net.fabricmc.loader.impl.launch.knot.KnotServer");
            cmd.add("--nogui");
            cmd.add("--world");
            cmd.add(dir.toPath().relativize(world.toPath()).toString());
            cmd.add("--port");
            cmd.add(String.valueOf(port));
            if (!online) cmd.add("--offline");
            OpenHouseMod.LOG.info("opening {} to friends: server on 127.0.0.1:{} ({})", worldDir, port, online ? "online mode" : "offline mode");

            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
            pb.environment().put(ServerRoom.HOST_TOKEN_ENV, hostToken);
            Process p = pb.start();
            proc = p;
            stdin = p.getOutputStream();
            if (state == State.STARTING && !detail.startsWith("Leaving out")) set(State.STARTING, "Starting the server...");

            List<String> lines = new ArrayList<>();
            dying = false;
            recent.clear();
            inError = false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter console = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "console.log")), StandardCharsets.UTF_8), true)) {
                String line;
                while ((line = r.readLine()) != null) {
                    console.println(line);
                    if (state == State.STARTING && lines.size() < 20000) lines.add(line);
                    recent.addLast(line);
                    if (recent.size() > 400) recent.removeFirst();
                    forward(line);

                    if (state == State.STARTING && !dying && (line.contains("Failed to start the minecraft server")
                            || line.contains("A mod crashed on startup") || line.contains("Exception in thread \"main\""))) {
                        dying = true;
                        Thread k = new Thread(() -> {
                            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                            if (p.isAlive()) p.destroyForcibly();
                        }, "openhouse end failed start");
                        k.setDaemon(true);
                        k.start();
                    }
                    onLine(line);
                }
            }
            int exit = p.waitFor();
            if (state == State.STOPPING) { set(State.STOPPED, ""); return null; }
            if (state == State.FAILED) return null;
            if (state == State.STARTING) return lines;

            OpenHouseMod.LOG.warn("Open to Friends: the server for {} stopped unexpectedly (exit {}){} - everything it printed is in {}", worldDir, exit,
                    crash != null ? " after " + crash : "", new File(dir, "console.log"));
            set(State.FAILED, crash != null ? crash : "The server stopped unexpectedly (exit " + exit + "). See openhouse/server/console.log.");
            return null;
        }
    }

    private void forward(String line) {
        boolean header = line.startsWith("[") && line.contains("]: ");
        if (header) inError = line.contains("/ERROR]") || line.contains("/FATAL]");
        else if (line.startsWith("Exception in thread ")) inError = true;

        if (line.startsWith(ServerRoom.CRASH_MARKER)) return;
        if (!inError || forwarded > 600) return;
        if (++forwarded > 600) {
            OpenHouseMod.LOG.warn("[world server] (more errors - see openhouse/server/console.log)");
            return;
        }
        OpenHouseMod.LOG.warn("[world server] {}", line);
    }

    private void crashed(String what) {
        if (state != State.READY || crash != null) return;
        String mod = null;
        try {

            List<String> tail = new ArrayList<>(recent);
            int from = 0;
            for (int k = tail.size() - 1; k >= 0; k--) if (tail.get(k).contains("/ERROR]")) { from = k; break; }
            String id = ServerMods.crashMod(tail.subList(from, tail.size()), serverMods);
            if (id != null) for (ServerMods.Entry e : serverMods) if (e.id.equals(id)) mod = e.name;
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't tell which mod the server crashed in: {}", t.toString());
        }
        crashMod = mod;
        crash = what;
        OpenHouseMod.LOG.warn("Open to Friends: the server for {} crashed{}: {}", worldDir, mod != null ? " in " + mod : "", what);
    }

    private void onLine(String line) {
        int i;
        if ((i = line.indexOf(ServerRoom.CRASH_MARKER)) >= 0) {
            crashed(line.substring(i + ServerRoom.CRASH_MARKER.length()).trim());
        } else if ((i = line.indexOf(ServerRoom.ROOM_MARKER)) >= 0) {

            String c = Rtc.normalizeCode(line.substring(i + ServerRoom.ROOM_MARKER.length()).trim());
            if (c != null) code = c;
            else OpenHouseMod.LOG.warn("not a room code in the server's output: {}", line);
        } else if ((i = line.indexOf("Preparing spawn area: ")) >= 0 && state == State.STARTING) {
            detail = "Preparing the world... " + line.substring(i + "Preparing spawn area: ".length()).trim();
        } else if (state == State.STARTING && !detail.startsWith("Leaving out") && (i = line.indexOf("Loading ")) >= 0 && line.endsWith(" mods:")) {

            detail = "Loading " + line.substring(i + 8, line.length() - 1).trim() + "...";
        } else if (state == State.STARTING && line.contains("Preparing level \"")) {
            detail = "Opening the world...";
        } else if (line.contains("Done (") && line.contains("For help")) {
            if (state == State.STARTING) set(State.READY, "");
        } else if (line.contains("FAILED TO BIND")) {
            set(State.FAILED, "The server couldn't use its port.");
        } else if (line.contains("Incompatible mods found") || line.contains("Exception in thread \"main\"")) {
            if (state == State.STARTING) detail = "The server hit a problem with your mods - see openhouse/server/console.log";
        }
    }

    public void stop() {
        if (state == State.STOPPED || state == State.FAILED) return;
        set(State.STOPPING, "Saving the world...");
        Thread t = new Thread(() -> stopAndWait(60000), "openhouse local server stop");
        t.setDaemon(true);
        t.start();
    }

    private void stopAndWait(long ms) {
        Process p = proc;
        if (p == null || !p.isAlive()) return;
        if (state != State.STOPPING) set(State.STOPPING, "Saving the world...");
        try {
            OutputStream in = stdin;
            if (in != null) { in.write("stop\n".getBytes(StandardCharsets.UTF_8)); in.flush(); }
        } catch (IOException ignored) { }
        try {
            if (!p.waitFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly();
        } catch (InterruptedException ignored) { }
    }

    static File modsFolder() {
        String prop = System.getProperty("fabric.modsFolder");
        if (prop != null && !prop.isEmpty()) return new File(prop);
        return new File(FabricLoader.getInstance().getGameDir().toFile(), "mods");
    }

    static int serverHeapMb() {
        long phys = 0;
        try {
            phys = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (Throwable ignored) { }
        long mb = phys / 4 / (1024 * 1024);
        return (int) Math.max(1536, Math.min(4096, mb));
    }

    static List<File> loaderClasspath() {
        String[] probes = {
            "net.fabricmc.loader.impl.launch.knot.KnotServer",
            "org.objectweb.asm.ClassReader",
            "org.objectweb.asm.tree.ClassNode",
            "org.objectweb.asm.commons.Remapper",
            "org.objectweb.asm.tree.analysis.Analyzer",
            "org.objectweb.asm.util.CheckClassAdapter",
            "org.spongepowered.asm.launch.MixinBootstrap",
            "com.llamalad7.mixinextras.MixinExtrasBootstrap",
        };
        Set<File> out = new LinkedHashSet<>();
        for (String name : probes) {
            try {
                Class<?> c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
                if (cs == null || cs.getLocation() == null || !"file".equals(cs.getLocation().getProtocol())) continue;
                File f = new File(cs.getLocation().toURI());
                if (f.isFile()) out.add(f);
            } catch (Throwable ignored) {

            }
        }
        return new ArrayList<>(out);
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private void writeSettings(File dir) throws IOException {
        Difficulty d = GameSettings.DIFFICULTY.value;
        int render = 8;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "server.properties")), StandardCharsets.UTF_8)) {
            w.write("# Written by OpenHouse for \"Open to Friends\" - rewritten each time a world is opened.\n");
            w.write("server-ip=\n");
            w.write("server-port=" + port + "\n");
            w.write("online-mode=" + online + "\n");

            w.write("motd=" + worldDir.replace("\n", " ") + OpenHouseMod.LAN_MARK + "\n");
            w.write("max-players=8\n");
            w.write("spawn-protection=0\n");
            w.write("difficulty=" + (d == null ? 2 : d.id()) + "\n");
            w.write("view-distance=" + render + "\n");
            w.write("pvp=true\n");
            w.write("white-list=false\n");
        }

        boolean real = uuid != null && (uuid.getMostSignificantBits() != 0 || uuid.getLeastSignificantBits() != 0);
        if (!real) OpenHouseMod.LOG.info("can't make {} an op on their opened world: that name has no Minecraft account (all such names share one id)", username);
        String id = real ? uuid.toString() : username;
        File ops = new File(dir, "ops.txt");
        String have = ops.isFile() ? new String(java.nio.file.Files.readAllBytes(ops.toPath()), StandardCharsets.UTF_8) : "";
        boolean listed = false;
        for (String l : have.split("\\R")) if (l.trim().equalsIgnoreCase(id)) listed = true;
        if (!listed) java.nio.file.Files.write(ops.toPath(), (have + (have.isEmpty() || have.endsWith("\n") ? "" : "\n") + id + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
