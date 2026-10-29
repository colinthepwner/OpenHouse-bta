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
import java.util.LinkedHashSet;
import java.util.List;
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
    volatile boolean announced;
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
        Thread t = new Thread(s::run, "openhouse local server");
        t.setDaemon(true);
        t.start();
        return s;
    }

    private void set(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
    }

    private void run() {
        File base = OpenHouseMod.dataDir();
        File dir = new File(base, "server");
        try {
            set(State.STARTING, "Getting the server ready...");
            File serverJar = Rtc.btaServer(new File(base, "lib"), s -> detail = s);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
            File world = new File(FabricLoader.getInstance().getGameDir().toFile(), "saves" + File.separator + worldDir);
            if (!new File(world, "level.dat").isFile()) throw new IOException("can't find the world's save (" + world + ")");
            port = freePort();
            writeSettings(dir);

            List<String> cmd = new ArrayList<>();
            cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath());
            cmd.add("-Xmx1536M");
            cmd.add("-Dfabric.modsFolder=" + modsFolder().getAbsolutePath());
            cmd.add("-Dopenhouse.lan=1");
            cmd.add("-Dopenhouse.dataDir=" + base.getAbsolutePath());
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
            set(State.STARTING, "Starting the server...");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stopAndWait(30000), "openhouse local server stop"));

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter console = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "console.log")), StandardCharsets.UTF_8), true)) {
                String line;
                while ((line = r.readLine()) != null) {
                    console.println(line);
                    onLine(line);
                }
            }
            int exit = p.waitFor();
            if (state == State.STOPPING) set(State.STOPPED, "");
            else set(State.FAILED, "The server stopped unexpectedly (exit " + exit + "). See openhouse/server/console.log.");
        } catch (Exception e) {
            OpenHouseMod.LOG.warn("Open to Friends failed", e);
            if (state != State.STOPPING) set(State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
            else set(State.STOPPED, "");
        }
    }

    private void onLine(String line) {
        int i;
        if ((i = line.indexOf(ServerRoom.ROOM_MARKER)) >= 0) {

            String c = Rtc.normalizeCode(line.substring(i + ServerRoom.ROOM_MARKER.length()).trim());
            if (c != null) code = c;
            else OpenHouseMod.LOG.warn("not a room code in the server's output: {}", line);
        } else if ((i = line.indexOf("Preparing spawn area: ")) >= 0 && state == State.STARTING) {
            detail = "Preparing the world... " + line.substring(i + "Preparing spawn area: ".length()).trim();
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
            w.write("motd=" + worldDir.replace("\n", " ") + "\n");
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
