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
    volatile boolean autoUpdate = true;

    volatile String password = "";
    volatile boolean whitelist = false;

    final java.util.Set<String> whitelistNames = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    public static final boolean LAN = System.getProperty("openhouse.lan") != null;

    public static final boolean OPENHOST = System.getProperty("openhouse.openhost") != null;

    public static final String ROOM_MARKER = "OPENHOUSE ROOM ";

    public static final String HOST_TOKEN_ENV = "OPENHOUSE_HOST_TOKEN";
    public static final int HOST_TOKEN_LENGTH = 16;
    private static final byte[] HOST_TOKEN = hostTokenFromEnv();

    private static byte[] hostTokenFromEnv() {
        String t = LAN ? System.getenv(HOST_TOKEN_ENV) : null;

        return t != null && t.length() == HOST_TOKEN_LENGTH ? t.getBytes(StandardCharsets.UTF_8) : null;
    }

    public static boolean isHostToken(String name) {
        byte[] t = HOST_TOKEN;
        return t != null && name != null && java.security.MessageDigest.isEqual(t, name.getBytes(StandardCharsets.UTF_8));
    }

    public static volatile String lanHostName;

    public static boolean isLanHost(net.minecraft.core.net.command.CommandSource s) {
        String h = lanHostName;
        if (!LAN || h == null || s == null) return false;
        net.minecraft.core.entity.player.Player p = s.getSender();
        return p != null && h.equalsIgnoreCase(p.username);
    }

    public static String loginRefusal(String name, java.util.UUID id, String proof, boolean host) {
        if (host || name == null) return null;
        ServerRoom r = INSTANCE;
        String pw = r.password;
        if (!pw.isEmpty() && !openhouse.RoomPassword.matches(pw, proof, name)) {
            LOG.info("{} {} - turned away", name, proof == null ? "didn't give the server's password" : "gave a wrong password");
            return proof == null ? openhouse.RoomPassword.NEEDED : openhouse.RoomPassword.WRONG;
        }
        if (r.whitelist && !r.whitelistNames.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            MinecraftServer server = MinecraftServer.getInstance();
            boolean realId = id != null && (id.getMostSignificantBits() != 0 || id.getLeastSignificantBits() != 0);
            if (!(realId && server != null && server.playerList != null && server.playerList.isOp(id))) {
                LOG.info("{} isn't on the whitelist - turned away", name);
                return "You're not on this server's whitelist.";
            }
        }
        return null;
    }

    public static final long STATS_MS = Long.getLong("openhouse.openhost.stats", 0L) * 1000;
    private static long statsFrom, statsTicks, statsTickAt, statsNanos, statsWorst;

    public static void statsTickStart() {
        statsTickAt = System.nanoTime();
    }

    public static void statsTickEnd(MinecraftServer s) {
        long took = System.nanoTime() - statsTickAt;
        long now = System.currentTimeMillis();
        if (statsFrom == 0) { statsFrom = now; return; }
        statsTicks++;
        statsNanos += took;
        statsWorst = Math.max(statsWorst, took);
        if (now - statsFrom < STATS_MS) return;
        Runtime r = Runtime.getRuntime();
        int players = s.playerList == null ? 0 : s.playerList.playerEntities.size();
        LOG.info(String.format(java.util.Locale.ROOT, "stats: tps %.1f  mspt avg %.1f max %.1f  mem %d/%d MB  players %d",
                statsTicks * 1000.0 / (now - statsFrom), statsNanos / 1e6 / Math.max(1, statsTicks), statsWorst / 1e6,
                (r.totalMemory() - r.freeMemory()) >> 20, r.maxMemory() >> 20, players));
        statsFrom = now;
        statsTicks = statsNanos = statsWorst = 0;
    }

    public static volatile boolean restartRequested;

    public static void restart(MinecraftServer s) {
        restartRequested = true;
        LOG.info("restarting the server - saving the world first");
        s.initiateShutdown();
    }

    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile("(?i)^(\\s*/?\\s*(?:oh|roomcode)\\s+password\\s+)(?!off\\s*$)(\\S.*)$");

    public static String hideSecret(String line) {
        if (line == null) return null;
        java.util.regex.Matcher m = SECRET.matcher(line);
        return m.matches() ? m.group(1) + "••••" : line;
    }

    synchronized void setPassword(String pw) {
        password = pw == null ? "" : pw;
        save();
    }

    synchronized void setWhitelist(boolean on) {
        whitelist = on;
        save();
    }

    synchronized boolean whitelistAdd(String name) {
        boolean added = whitelistNames.add(name.toLowerCase(java.util.Locale.ROOT));
        if (added) save();
        return added;
    }

    synchronized boolean whitelistRemove(String name) {
        boolean removed = whitelistNames.remove(name.toLowerCase(java.util.Locale.ROOT));
        if (removed) save();
        return removed;
    }

    java.util.List<String> whitelisted() {
        synchronized (whitelistNames) { return new java.util.ArrayList<>(whitelistNames); }
    }

    public static java.util.UUID playerId(String name, java.util.UUID sent, boolean host) {
        boolean none = sent == null || (sent.getMostSignificantBits() == 0 && sent.getLeastSignificantBits() == 0);
        if (host || !none || name == null) return sent;
        java.util.UUID id = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        if (!LAN) adoptSharedSave(id);
        return id;
    }

    static final String ADOPTED_NOTE = ".openhouse-handed-on";
    private static void adoptSharedSave(java.util.UUID id) {
        File dir = playersDir();
        if (dir == null) return;
        File shared = new File(dir, new java.util.UUID(0, 0) + ".dat");
        File own = new File(dir, id + ".dat");
        File note = new File(dir, shared.getName() + ADOPTED_NOTE);
        if (!shared.isFile() || own.exists() || note.exists()) return;
        try {
            java.nio.file.Files.copy(shared.toPath(), own.toPath());
            java.nio.file.Files.write(note.toPath(), (id + "\n").getBytes(StandardCharsets.UTF_8));
            LOG.info("the save account-less players used to share is now {}'s own ({})", id, own.getName());
        } catch (java.io.IOException e) {
            LOG.info("couldn't hand the shared account-less save to {} ({}) - starting them fresh", id, e.toString());
        }
    }

    private volatile Host host;
    private volatile String target;
    volatile String liveCode;

    private ServerRoom() { }

    public static ServerRoom get() { return INSTANCE; }

    public synchronized void serverStarted(MinecraftServer server) {
        load();
        if (LAN || OPENHOST) watchParent(server);
        if (!LAN && !OPENHOST && autoUpdate) updateInBackground();
        if (OPENHOST) {
            windowTitle(null);

            HostUi.read(HostUi.KEY, "modern");
            HostUi.read(HostUi.THEME, "dark");
        }
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

    public static void waitForWorld() {
        String gate = System.getProperty("openhouse.worldGate");
        if (!LAN || gate == null || gate.isEmpty()) return;
        File f = new File(gate);
        if (f.isFile()) return;
        LOG.info("waiting for the host's game to finish saving the world");
        ProcessHandle parent = null;
        try { parent = ProcessHandle.of(Long.parseLong(System.getProperty("openhouse.parentPid", ""))).orElse(null); } catch (Exception ignored) { }
        long until = System.currentTimeMillis() + 10 * 60 * 1000L;
        boolean interrupted = false;
        while (!f.isFile()) {
            if ((parent != null && !parent.isAlive()) || System.currentTimeMillis() > until) {
                LOG.warn("the host's game never finished closing the world - stopping without opening it");

                Runtime.getRuntime().halt(0);
            }

            try { Thread.sleep(50); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        LOG.info("the world is saved - opening it");
    }

    private volatile String crash;
    public static final String CRASH_MARKER = "OPENHOUSE CRASHED ";

    public static final String ROOMFAIL_MARKER = "OPENHOUSE ROOMFAIL ";

    public static volatile Throwable lastTickError;

    public void crashed(Throwable t) {
        if (!(LAN || OPENHOST) || crash != null) return;
        String s = t == null ? "an error" : t.toString();

        s = s.replace('\n', ' ').replace('\r', ' ');
        if (s.length() > 200) s = s.substring(0, 200) + "...";
        crash = s;
        System.out.println(CRASH_MARKER + s);
        System.out.flush();
    }

    public synchronized void serverStopping() {

        if (LAN || OPENHOST) {
            MinecraftServer server = MinecraftServer.getInstance();

            String c = crash;
            String why = !LAN ? (c == null ? "The server stopped." : "The server crashed (" + shortName(c) + ") - try again in a minute.")
                    : c == null ? "The host closed their world." : "The world crashed (" + shortName(c) + ").";
            try {
                if (server != null && server.playerList != null)
                    for (net.minecraft.server.entity.player.PlayerServer p : new java.util.ArrayList<>(server.playerList.playerEntities))
                        p.playerNetServerHandler.kickPlayer(why);
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

    static String shortName(String throwable) {
        int colon = throwable.indexOf(':');
        String cls = colon >= 0 ? throwable.substring(0, colon) : throwable;
        int dot = cls.lastIndexOf('.');
        cls = dot >= 0 ? cls.substring(dot + 1) : cls;
        return cls.length() > 60 ? cls.substring(0, 60) : cls;
    }

    synchronized void startHost() {
        if (host != null || target == null) return;
        final Host[] self = new Host[1];

        Host h = new Host(dataDir, code, token, target, shareManifest(), s -> { if (Rtc.coarseProgress(s)) LOG.info(s); }, (c, t) -> {

            if (host != self[0]) return;
            liveCode = c;
            boolean wasTaken = takenCode != null;
            takenCode = null;
            takenCount = 0;
            if (LAN) { System.out.println(ROOM_MARKER + c); System.out.flush(); }
            if (OPENHOST) rememberLiveRoom(c, t);
            if (!c.equals(code) || !t.equals(token)) {
                code = c;
                token = t;
                save();
            }
            if (!LAN) {
                if (wasTaken) LOG.info("Room code {} is this server's now - players can join with it.", c);

                if (!Rtc.isPicked(c) && !hinted) {
                    hinted = true;
                    LOG.info("This code changes every time the server starts. For one that stays, type: oh set <name>   (6-16 letters/digits, e.g. oh set MYWORLD)");
                }
            }
            if (OPENHOST) windowTitle("room code " + c);
        });

        h.onWarn = s -> LOG.warn(s);
        h.onFail = s -> {
            LOG.warn(s);
            if (LAN) { System.out.println(ROOMFAIL_MARKER + s); System.out.flush(); }
            if (OPENHOST) windowTitle("room codes are off - see the log");
        };
        h.onTaken = c -> {
            if (host != self[0] || !Rtc.isPicked(c)) return;
            int n = ++takenCount;
            if (n == 2 || (n > 2 && n % 20 == 2)) {
                takenCode = c;
                LOG.warn("Room code {} is taken - another server is using it, so players can't join this one with it. "
                        + "Pick a different code: oh set <name> (6-16 letters/digits), or code= in config/openhouse.properties.", c);
                if (OPENHOST) windowTitle("room code " + c + " is taken - oh set <name>");
            }
        };
        self[0] = h;
        host = h;
        h.pool(FabricLoader.getInstance().getGameDir().toFile(), shareFolders);
        h.start();
    }

    volatile String takenCode;
    private int takenCount;
    private boolean hinted;

    static void windowTitle(String what) {
        if (java.awt.GraphicsEnvironment.isHeadless()) return;
        String title = "OpenHost" + (what != null ? " - " + what : "");
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                HostWindow mine = HostWindow.current;
                for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                    if (mine != null && f == mine.frame) continue;
                    String t = f.getTitle();
                    if (f.isDisplayable() && t != null && (t.equals("Minecraft server") || t.startsWith("OpenHost"))) f.setTitle(title);
                }
            } catch (Throwable ignored) { }
        });
    }

    synchronized void stopHost() {
        Host h = host;
        host = null;
        liveCode = null;
        takenCode = null;
        takenCount = 0;
        if (h != null) h.stop();
    }

    boolean running() { return host != null; }

    private java.io.File shareManifest() {
        if (!shareMods) return null;

        String given = System.getProperty("openhouse.shareManifest");
        if (given != null && !given.isEmpty() && new java.io.File(given).isFile()) {
            LOG.info("sharing the host's mods with players who join by code");
            return new java.io.File(given);
        }
        try {

            String real = System.getProperty("openhouse.openhost.mods");
            String prop = real != null && !real.isEmpty() ? real : System.getProperty("fabric.modsFolder");
            java.io.File folder = prop != null && !prop.isEmpty() ? new java.io.File(prop) : new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "mods");
            java.util.List<openhouse.ModShare.Mod> mods = openhouse.ModShare.scan(folder);

            mods.removeIf(m -> openhouse.mod.OpenHouseMod.MOD_ID.equals(m.id));

            java.util.List<openhouse.ModShare.Mod> packs = openhouse.ModShare.texturePacks(new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "texturepacks"));
            if (!packs.isEmpty()) LOG.info("sharing {} texture pack{} with players who join by code (theirs to turn on)", packs.size(), packs.size() == 1 ? "" : "s");
            mods.addAll(packs);
            int modCount = mods.size() - packs.size();
            LOG.info("sharing {} mod{} with players who join by code", modCount, modCount == 1 ? "" : "s");

            dataDir.mkdirs();
            java.io.File cfg = openhouse.ModShare.zipConfigs(FabricLoader.getInstance().getConfigDir().toFile(),
                    new java.io.File(dataDir, "share-config.zip"), n -> n.equalsIgnoreCase(file.getName()));
            if (cfg != null) mods.add(openhouse.ModShare.extra(cfg, "openhouse:config", "the server's mod settings"));
            java.io.File table = uselessNumericalTable();
            if (table != null) mods.add(openhouse.ModShare.extra(table, "openhouse:uselessnumerical", "the world's id table"));
            return openhouse.ModShare.writeHostManifest(mods, new java.io.File(dataDir, "share.json"));
        } catch (Exception e) {
            LOG.warn("can't list this server's mods to share: {}", e.toString());
            return null;
        }
    }

    private java.io.File lastRoomFile() { return new java.io.File(dataDir, "live-room.properties"); }

    private void rememberLiveRoom(String c, String t) {
        Properties p = new Properties();
        p.setProperty("code", c);
        p.setProperty("token", t);
        dataDir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(lastRoomFile())) {
            p.store(out, "OpenHost: the room code this run of OpenHost is using (kept across Restart now)");
        } catch (Exception e) {
            LOG.warn("can't note the live room code for a restart: {}", e.toString());
        }
    }

    private java.io.File uselessNumericalTable() {
        if (!FabricLoader.getInstance().isModLoaded("uselessnumerical")) return null;
        MinecraftServer s = MinecraftServer.getInstance();
        if (s == null) return null;
        String level = s.argWorld != null ? s.argWorld : s.propertyManager.getStringProperty("level-name", "world");
        java.io.File world = new java.io.File(level);
        try {
            Class<?> c = Class.forName("gungun974.uselessnumerical.MinecraftIdsConfiguration");
            Object conf = c.getMethod("getInstance").invoke(null);
            c.getMethod("saveWorldConfiguration", java.io.File.class).invoke(conf, world);
        } catch (Throwable t) {
            LOG.info("couldn't have UselessNumerical write its id table ({}) - sharing the saved one", t.toString());
        }
        java.io.File saved = new java.io.File(world, "uselessNumericalSave.dat");
        if (!saved.isFile()) return null;
        try {
            java.io.File copy = new java.io.File(dataDir, "share-uselessnumerical.dat");
            java.nio.file.Files.copy(saved.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return copy;
        } catch (java.io.IOException e) {
            LOG.warn("can't copy the world's UselessNumerical table to share: {}", e.toString());
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
                LOG.info(OPENHOST ? "OpenHost was closed - stopping the server" : "the game that opened this world has closed - stopping the server");
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

    volatile String shareFolders = openhouse.ShareFolders.DEFAULT;

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
        if (!c.isEmpty() && code.isEmpty()) LOG.warn("code \"{}\" in {} isn't a room code (6-16 letters/digits) - picking one", c, file.getName());
        address = p.getProperty("address", "").trim();
        token = p.getProperty("token", "").trim();

        shareFolders = p.getProperty(openhouse.ShareFolders.SETTING, openhouse.ShareFolders.DEFAULT).trim();

        if (!Rtc.isPicked(code)) {
            code = "";
            token = "";

            if (OPENHOST && Boolean.getBoolean("openhouse.openhost.restarted")) {
                Properties last = new Properties();
                try (InputStreamReader in = new InputStreamReader(new FileInputStream(lastRoomFile()), StandardCharsets.UTF_8)) {
                    last.load(in);
                    String c2 = Rtc.normalizeCode(last.getProperty("code", ""));
                    if (c2 != null && !Rtc.isPicked(c2)) { code = c2; token = last.getProperty("token", ""); }
                } catch (Exception ignored) { }
            }
        }
        shareMods = !"false".equalsIgnoreCase(p.getProperty("shareMods", "true").trim());
        autoUpdate = !"false".equalsIgnoreCase(p.getProperty("autoUpdate", "true").trim());

        password = p.getProperty("password", "");
        whitelist = "true".equalsIgnoreCase(p.getProperty("whitelist", "false").trim());
        whitelistNames.clear();
        for (String n : p.getProperty("whitelistPlayers", "").split(",")) {
            n = n.trim().toLowerCase(java.util.Locale.ROOT);
            if (!n.isEmpty()) whitelistNames.add(n);
        }
    }

    @SuppressWarnings("unused") private volatile Process swap;

    private static final java.util.Map<String, Boolean> ACCOUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Map<String, String> ACCOUNT_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Boolean mojangAccount(String name) {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return Boolean.FALSE;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Boolean known = ACCOUNTS.get(key);
        if (known != null) return known;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "OpenHouse/" + openhouse.Version.VERSION);
            int code = c.getResponseCode();
            Boolean result = code == 200 ? Boolean.TRUE : code == 404 ? Boolean.FALSE : null;
            if (code == 200) {
                String body;
                try (java.io.InputStream in = c.getInputStream()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").matcher(body);
                if (m.find()) {
                    String h = m.group(1).toLowerCase(java.util.Locale.ROOT);
                    ACCOUNT_IDS.put(key, h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20));
                }
            }
            if (result != null) ACCOUNTS.put(key, result);
            else LOG.info("Mojang's name lookup answered {} for {} - keeping the account check", code, name);
            return result;
        } catch (Exception e) {
            LOG.info("couldn't ask Mojang whether {} is an account ({}) - keeping the account check", name, e.toString());
            return null;
        }
    }

    public static boolean playedHere(String name) {
        String id = ACCOUNT_IDS.get(name.toLowerCase(java.util.Locale.ROOT));
        File dir = playersDir();
        if (id == null || dir == null) return true;
        return new File(dir, id + ".dat").exists() || new File(dir, name + ".dat").exists();
    }

    private static File playersDir() {
        try {
            MinecraftServer server = MinecraftServer.getInstance();
            Object st = server == null || server.getDimensionWorld(0) == null ? null : server.getDimensionWorld(0).getLevelStorage();
            for (Class<?> k = st == null ? null : st.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField("playersDirectory");
                    f.setAccessible(true);
                    Object v = f.get(st);
                    return v instanceof File ? (File) v : null;
                } catch (NoSuchFieldException ignored) { }
            }
        } catch (Exception e) {
            LOG.info("couldn't find the world's players folder ({})", e.toString());
        }
        return null;
    }

    private void updateInBackground() {
        Thread t = new Thread(() -> {
            try {
                String mine = FabricLoader.getInstance().getModContainer("openhouse")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
                openhouse.Update.Release r = openhouse.Update.latestMod(openhouse.Update.gameVersion(mine));
                if (!r.newer()) return;
                File self = Rtc.self();
                if (!self.isFile()) return;
                LOG.info("OpenHouse {} is out (this server has {}) - downloading it", r.version, openhouse.Version.VERSION);
                File jar = openhouse.Update.download(r, new File(dataDir, "update"), null);
                swap = openhouse.Update.startSwap(self, jar, dataDir, java.util.Collections.singletonList(self.getParentFile().getAbsolutePath()),
                        java.util.Collections.<String>emptyList(), new File(System.getProperty("user.dir")));
                LOG.info("OpenHouse {} goes in when this server stops - restart the server to use it (players on {} can't join by code until then). "
                        + "If it isn't in after a restart, replace {} with {} yourself.", r.version, r.version, self.getName(), jar.getPath());
            } catch (Exception e) {
                LOG.info("couldn't check for an OpenHouse update: {}", e.toString());
            }
        }, "openhouse update check");
        t.setDaemon(true);
        t.start();
    }

    static String escapeValue(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == ' ' && i == 0) b.append("\\ ");
            else b.append(c);
        }
        return b.toString();
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
                    + "# The room code players type to join: a name of your own, 6-16 letters/digits\n"
                    + "# (another server already using it? the console says so - pick another). Empty = a\n"
                    + "# new random one (like XQZ273) every time the server starts.\n"
                    + "code=" + (Rtc.isPicked(code) ? code : "") + "\n"
                    + "\n"
                    + "# Where room players are sent. Empty = this server (its server-ip / server-port).\n"
                    + "address=" + address + "\n"
                    + "\n"
                    + "# Offer this server's mods to players who join by code, so they play with the same\n"
                    + "# mods (they're asked first). true or false\n"
                    + "shareMods=" + shareMods + "\n"
                    + "\n"
                    + "# Folders of a mod's own beside this server that players may add to, so everyone\n"
                    + "# can use what anyone brought - BTEmu's game cartridges, for instance. Folder\n"
                    + "# names separated by commas, or off for none.\n"
                    + openhouse.ShareFolders.SETTING + "=" + shareFolders + "\n"
                    + "\n"
                    + "# Download newer OpenHouse versions by itself; they go in when the server stops.\n"
                    + "# true or false\n"
                    + "autoUpdate=" + autoUpdate + "\n"
                    + "\n"
                    + "# A password players need to join (OpenHouse asks them once and remembers it). Empty = none.\n"
                    + "# Change it any time with /oh password <new> or /oh password off - players already on stay.\n"
                    + "password=" + escapeValue(password) + "\n"
                    + "\n"
                    + "# Only let in the players listed below (and ops). true or false. /oh whitelist on|off|add|remove\n"
                    + "whitelist=" + whitelist + "\n"
                    + "# Player names, separated by commas.\n"
                    + "whitelistPlayers=" + String.join(", ", whitelisted()) + "\n"
                    + "\n"
                    + "# Keeps your code registered to this server between restarts. Don't share it; empty it to\n"
                    + "# start fresh.\n"
                    + "token=" + (Rtc.isPicked(code) ? token : "") + "\n");
        } catch (Exception e) {
            LOG.warn("can't write {}: {}", file, e.toString());
        }
    }
}
