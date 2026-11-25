package openhouse.mod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import openhouse.OpenHostList;

public final class MoveToOpenHost {
    private MoveToOpenHost() { }

    public static final String MOVED = "openhouse-moved.properties";

    static File game() { return FabricLoader.getInstance().getGameDir().toFile(); }

    static String blocked() {
        String v = LocalServer.gameVersion();
        if (!"8.0.1".equals(v)) return "OpenHost runs BTA 8.0.1 worlds. This game is BTA " + v + ".";
        try { if (!openhouse.Rtc.self().isFile()) return "This game isn't running OpenHouse from a jar."; } catch (IOException e) { return e.getMessage(); }
        if (LocalServer.hosting()) return "Close the world first.";
        return null;
    }

    static OpenHostList.Server move(String world, Consumer<String> say) throws IOException {
        File g = game(), src = new File(new File(g, "saves"), world);
        if (!new File(src, "level.dat").isFile()) throw new IOException("can't find the world " + world);
        File base = new File(g, "openhost");
        File srv = new File(base, world.replaceAll("[^A-Za-z0-9 _.-]", "_"));
        for (int n = 2; srv.exists(); n++) srv = new File(base, world.replaceAll("[^A-Za-z0-9 _.-]", "_") + " " + n);
        if (!srv.mkdirs()) throw new IOException("can't make " + srv);
        openhouse.OhCrash.note("moving world " + world + " to OpenHost at " + srv);

        say.accept("Backing up the world...");
        WorldGuard.backup(src, "before-openhost");

        say.accept("Setting up the server...");
        Files.copy(openhouse.Rtc.self().toPath(), new File(srv, "OpenHost.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File mods = new File(srv, "mods");
        mods.mkdirs();
        File[] js = CrashReports.mods().listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        File self = openhouse.Rtc.self().getCanonicalFile();
        java.util.List<String> copied = new java.util.ArrayList<>();
        if (js != null) for (File j : js) {

            if (j.getCanonicalFile().equals(self) || "openhouse".equals(modId(j))) continue;
            Files.copy(j.toPath(), new File(mods, j.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            copied.add(j.getName());
        }
        ModSync.moved(srv, copied);
        File cfg = new File(g, "config");
        if (cfg.isDirectory()) Relaunch.copyTree(cfg, new File(srv, "config"));

        File otf = new File(OpenHouseMod.dataDir(), "server/config/openhouse.properties");
        if (otf.isFile()) { new File(srv, "config").mkdirs(); Files.copy(otf.toPath(), new File(srv, "config/openhouse.properties").toPath(), StandardCopyOption.REPLACE_EXISTING); }

        File lib = new File(OpenHouseMod.dataDir(), "lib");
        if (lib.isDirectory()) Relaunch.copyTree(lib, new File(srv, "openhouse/lib"));
        File crash = new File(g, openhouse.OhCrash.FILE);
        if (crash.isFile()) Files.copy(crash.toPath(), new File(srv, openhouse.OhCrash.FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);

        int port = freePort();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        String sid = mc == null || mc.session == null ? null : mc.session.sessionId;
        boolean online = sid != null && !sid.isEmpty() && !sid.equals("-");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(srv, "server.properties")), StandardCharsets.UTF_8)) {
            w.write("# Made by OpenHouse's Move to OpenHost. OpenHost's Settings change most of these.\n");
            w.write("level-name=" + world + "\n");
            w.write("server-ip=\n");
            w.write("server-port=" + port + "\n");
            w.write("online-mode=" + online + "\n");
            w.write("motd=" + world + "\n");
            w.write("max-players=20\n");
            w.write("view-distance=10\n");
            w.write("spawn-protection=0\n");
            w.write("pvp=true\n");
            w.write("white-list=false\n");
            w.write("openhost-ui=modern\n");
        }

        if (mc != null && mc.session != null)
            Files.write(new File(srv, "ops.txt").toPath(), ((mc.session.uuid != null ? mc.session.uuid.toString() : mc.session.username) + "\n").getBytes(StandardCharsets.UTF_8));

        String pw = otfPassword(otf);
        if (!pw.isEmpty()) PasswordStore.put("server:127.0.0.1:" + port, openhouse.RoomPassword.key(pw));

        say.accept("Moving the world...");
        File dest = new File(srv, world);
        try {
            Files.move(src.toPath(), dest.toPath());
        } catch (IOException e) {

            Relaunch.copyTree(src, dest);
            WorldGuard.deleteTree(src);
        }
        new File(dest, "session.lock").delete();

        Files.write(new File(srv, MOVED).toPath(), ("# moved here from single player by OpenHouse\nfrom="
                + new File(g, "saves").getAbsolutePath() + "\nworld=" + world + "\n").getBytes(StandardCharsets.UTF_8));
        OpenHostList.add(srv);
        openhouse.OhCrash.note("world " + world + " moved to OpenHost, port " + port);
        OpenHostList.Server s = OpenHostList.of(srv);
        if (s == null) throw new IOException("the server was made but can't be read back (" + srv + ")");
        return s;
    }

    static String otfPassword(File f) {
        java.util.Properties p = new java.util.Properties();
        if (f.isFile()) try (java.io.InputStream in = new java.io.FileInputStream(f)) { p.load(in); } catch (IOException ignored) { }
        return p.getProperty("password", "");
    }

    static String modId(File jar) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar)) {
            java.util.zip.ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return null;
            try (java.io.InputStream in = z.getInputStream(e)) {
                java.util.Map<String, Object> j = openhouse.Json.obj(openhouse.Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                return j == null ? null : openhouse.Json.str(j.get("id"));
            }
        } catch (Exception e) { return null; }
    }

    static int freePort() {
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (OpenHostList.Server s : OpenHostList.all()) taken.add(s.port);
        for (int p = 25565; p < 25665; p++) {
            if (taken.contains(p)) continue;
            try (java.net.ServerSocket ss = new java.net.ServerSocket(p)) { return p; } catch (IOException ignored) { }
        }
        return 25565;
    }

    public static void stop(OpenHostList.Server s) {
        try {
            File f = new File(s.folder, "openhouse/control.txt");
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), "stop".getBytes(StandardCharsets.UTF_8));
            openhouse.OhCrash.note("asked OpenHost in " + s.folder.getName() + " to stop");
        } catch (IOException e) { OpenHouseMod.LOG.warn("couldn't ask OpenHost to stop: {}", e.toString()); }
    }

    static String state(OpenHostList.Server s) {
        try {
            File f = new File(s.folder, "openhouse/running.txt");
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(" ")[0] : null;
        } catch (IOException e) { return null; }
    }

    public static boolean running(OpenHostList.Server s, boolean ready) {
        File f = new File(s.folder, "openhouse/running.txt");
        if (f.isFile()) {
            try {
                String[] w = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(" ");
                boolean alive = w.length > 1 && ProcessHandle.of(Long.parseLong(w[1])).map(ProcessHandle::isAlive).orElse(false);
                return alive && (!ready || w[0].equals("up"));
            } catch (Exception e) { return false; }
        }
        return portTaken(s.port);
    }

    static boolean portTaken(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) { return false; } catch (IOException e) { return true; }
    }

    static Process start(File srv) throws IOException {
        File jar = OpenHostList.jar(srv);
        if (jar == null) throw new IOException("there's no OpenHost jar in " + srv);
        String home = System.getProperty("java.home");
        File javaw = new File(home, "bin/javaw.exe");
        File java = javaw.isFile() ? javaw : new File(home, "bin/java");
        File log = new File(srv, "openhouse/started-by-game.log");
        log.getParentFile().mkdirs();

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        String owner = mc == null || mc.session == null || mc.session.username == null ? "" : mc.session.username;
        ProcessBuilder pb = new ProcessBuilder(java.getAbsolutePath(), "-Dopenhouse.openhost.minimized=true", "-Dopenhouse.openhost.owner=" + owner,
                "-jar", jar.getAbsolutePath()).directory(srv)
                .redirectErrorStream(true).redirectOutput(log);
        Process p = pb.start();
        try { p.getOutputStream().close(); } catch (IOException ignored) { }
        openhouse.OhCrash.note("started OpenHost in " + srv.getName());
        return p;
    }
}
