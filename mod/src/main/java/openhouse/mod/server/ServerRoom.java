package openhouse.mod.server;

import openhouse.Host;
import openhouse.Rtc;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerRoom {
    public static final Logger LOG = LoggerFactory.getLogger("OpenHouse");
    static final ServerRoom INSTANCE = new ServerRoom();

    private final File file = new File(FabricLoader.getInstance().getConfigDir().toFile(), "openhouse.properties");

    private final File dataDir = System.getProperty("openhouse.dataDir") != null ? new File(System.getProperty("openhouse.dataDir"))
            : new File(FabricLoader.getInstance().getGameDir().toFile(), "openhouse");

    volatile boolean enabled = true;
    volatile String code = "";
    volatile String address = "";
    volatile String token = "";
    volatile boolean shareMods = true;

    static final boolean LAN = System.getProperty("openhouse.lan") != null;

    public static final String ROOM_MARKER = "OPENHOUSE ROOM ";

    private volatile Host host;
    private volatile String target;
    volatile String liveCode;

    private ServerRoom() { }

    public static ServerRoom get() { return INSTANCE; }

    public synchronized void serverStarted(MinecraftServer server) {
        load();
        if (LAN) watchParent(server);
        String ip = server.propertyManager.getStringProperty("server-ip", "");
        int port = server.argPort >= 0 ? server.argPort : server.propertyManager.getIntProperty("server-port", 25565);

        String h = ip.isEmpty() || ip.equals("0.0.0.0") || ip.equals("::") ? "127.0.0.1" : ip;
        target = address.isEmpty() ? h + ":" + port : address;
        save();
        if (!enabled) {
            LOG.info("Room codes are off ({} or /oh on)", file.getPath());
            return;
        }
        startHost();
    }

    public synchronized void serverStopping() {

        if (LAN) {
            MinecraftServer server = MinecraftServer.getInstance();
            try {
                if (server != null && server.playerList != null)
                    for (net.minecraft.server.entity.player.PlayerServer p : new java.util.ArrayList<>(server.playerList.playerEntities))
                        p.playerNetServerHandler.kickPlayer("The host closed their world.");
            } catch (Throwable t) {
                LOG.warn("couldn't tell players the world is closing: {}", t.toString());
            }
            Thread t = new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                stopHost();
            }, "openhouse room stop");
            t.setDaemon(true);
            t.start();
            return;
        }
        stopHost();
    }

    synchronized void startHost() {
        if (host != null || target == null) return;
        final Host[] self = new Host[1];
        Host h = new Host(dataDir, code, token, target, shareManifest(), s -> LOG.info(s), (c, t) -> {

            if (host != self[0]) return;
            liveCode = c;
            if (LAN) { System.out.println(ROOM_MARKER + c); System.out.flush(); }
            if (!c.equals(code) || !t.equals(token)) {
                code = c;
                token = t;
                save();
            }
        });
        self[0] = h;
        host = h;
        h.start();
    }

    synchronized void stopHost() {
        Host h = host;
        host = null;
        liveCode = null;
        if (h != null) h.stop();
    }

    boolean running() { return host != null; }

    private java.io.File shareManifest() {
        if (!shareMods) return null;
        try {
            String prop = System.getProperty("fabric.modsFolder");
            java.io.File folder = prop != null && !prop.isEmpty() ? new java.io.File(prop) : new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "mods");
            java.util.List<openhouse.ModShare.Mod> mods = openhouse.ModShare.scan(folder);
            LOG.info("sharing {} mod{} with players who join by code", mods.size(), mods.size() == 1 ? "" : "s");
            return openhouse.ModShare.writeHostManifest(mods, new java.io.File(dataDir, "share.json"));
        } catch (Exception e) {
            LOG.warn("can't list this server's mods to share: {}", e.toString());
            return null;
        }
    }

    private void watchParent(MinecraftServer server) {
        String pid = System.getProperty("openhouse.parentPid", "");
        if (pid.isEmpty()) return;
        try {
            java.util.Optional<ProcessHandle> parent = ProcessHandle.of(Long.parseLong(pid));
            if (!parent.isPresent()) { server.initiateShutdown(); return; }
            parent.get().onExit().thenRun(() -> {
                LOG.info("the game that opened this world has closed - stopping the server");
                server.initiateShutdown();
            });
        } catch (Exception e) {
            LOG.warn("can't watch the game that started this server: {}", e.toString());
        }
    }

    int players() { Host h = host; return h == null ? 0 : h.players; }

    synchronized void changeCode(String newCode) {
        stopHost();
        code = newCode;
        token = "";
        save();
        if (enabled) startHost();
    }

    synchronized void setEnabled(boolean on) {
        enabled = on;
        save();
        if (on) startHost(); else stopHost();
    }

    private void load() {
        Properties p = new Properties();
        if (file.isFile()) {
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (Exception e) {
                LOG.warn("can't read {}: {} - using defaults", file, e.toString());
            }
        }
        enabled = !"false".equalsIgnoreCase(p.getProperty("enabled", "true").trim());
        String c = p.getProperty("code", "").trim();
        code = c.isEmpty() ? "" : (Rtc.normalizeCode(c) != null ? Rtc.normalizeCode(c) : "");
        if (!c.isEmpty() && code.isEmpty()) LOG.warn("code \"{}\" in {} isn't a room code (5 letters/digits, no 0 1 I O) - picking one", c, file.getName());
        address = p.getProperty("address", "").trim();
        token = p.getProperty("token", "").trim();
        shareMods = !"false".equalsIgnoreCase(p.getProperty("shareMods", "true").trim());
    }

    synchronized void save() {
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write("# OpenHouse: players join this server by room code (Multiplayer > Room Code with the\n"
                    + "# mod, or the OpenHouse helper) - no port forwarding.\n"
                    + "# In game / console: /oh, and for ops /oh new, /oh set <CODE>, /oh on|off\n"
                    + "\n"
                    + "# true or false\n"
                    + "enabled=" + enabled + "\n"
                    + "\n"
                    + "# The room code: 5 letters/digits (no 0, 1, I or O). Empty = pick one (it's saved here).\n"
                    + "code=" + code + "\n"
                    + "\n"
                    + "# Where room players are sent. Empty = this server (its server-ip / server-port).\n"
                    + "address=" + address + "\n"
                    + "\n"
                    + "# Offer this server's mods to players who join by code, so they play with the same\n"
                    + "# mods (they're asked first). true or false\n"
                    + "shareMods=" + shareMods + "\n"
                    + "\n"
                    + "# Keeps the code registered to this server between restarts. Don't share it; empty it to\n"
                    + "# start fresh.\n"
                    + "token=" + token + "\n");
        } catch (Exception e) {
            LOG.warn("can't write {}: {}", file, e.toString());
        }
    }
}
