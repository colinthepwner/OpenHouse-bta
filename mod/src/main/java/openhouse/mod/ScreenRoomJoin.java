package openhouse.mod;

import openhouse.Joiner;
import openhouse.ModShare;
import openhouse.Rtc;
import openhouse.Version;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;
import net.minecraft.client.gui.server.ScreenSelectServer;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenRoomJoin extends Screen {
    private enum Phase { LOOKUP, CHECK, OWN, PACKS, PACKS_GOT, VERSION, ASK, DOWNLOAD, RESTART, FAILED, CONNECT }

    private final String code;
    private final Joiner joiner;
    private Phase phase = Phase.LOOKUP;
    private List<ModShare.Mod> hostMods = new ArrayList<>(), missing = new ArrayList<>(), packsWanted = new ArrayList<>();
    private final List<String> packsGot = new ArrayList<>();
    private long packsGotAt;
    private String problem = "";
    private ButtonElement back, retry, download, anyway, update;
    private boolean hostOlder;
    private long checkStarted;

    private volatile List<ModShare.Mod> ownMissing;
    private final Set<String> rebuilt = java.util.Collections.synchronizedSet(new HashSet<>());
    private long ownStarted;

    public ScreenRoomJoin(Screen parent, String code) {
        super(parent);
        this.code = code;
        this.joiner = OpenHouseMod.joinerFor(code);
    }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        retry = add(new ButtonElement(1, width / 2 - 100, height / 4 + 108, t.translateKey("gui.openhouse.button.retry")));
        download = add(new ButtonElement(2, width / 2 - 100, height / 4 + 84, 98, 20, t.translateKey("gui.openhouse.mods.button.download")));
        anyway = add(new ButtonElement(3, width / 2 + 2, height / 4 + 84, 98, 20, t.translateKey("gui.openhouse.mods.button.anyway")));
        back = add(new ButtonElement(0, width / 2 - 100, height / 4 + 132, t.translateKey("gui.openhouse.button.back")));
        update = add(new ButtonElement(4, width / 2 - 100, height / 4 + 84, t.translateKey("gui.openhouse.version.button.update")));
        layout();
    }

    private void layout() {
        Joiner.State s = joiner.state;
        boolean ended = phase == Phase.LOOKUP && (s == Joiner.State.NOT_FOUND || s == Joiner.State.NO_ANSWER || s == Joiner.State.FAILED);
        retry.visible = retry.enabled = ended;
        download.visible = download.enabled = phase == Phase.ASK;
        anyway.visible = anyway.enabled = phase == Phase.ASK;
        update.visible = update.enabled = phase == Phase.VERSION && !hostOlder;
        back.visible = back.enabled = phase != Phase.RESTART;
    }

    @Override public void tick() {
        Joiner.State s = joiner.state;
        switch (phase) {
            case LOOKUP:
                if (s == Joiner.State.READY && joiner.port > 0) {

                    if (code.equals(OpenHouseMod.PACK)) { connect(); break; }
                    phase = Phase.CHECK;
                    checkStarted = System.currentTimeMillis();
                    joiner.requestModList();
                }
                break;
            case CHECK:
                if (joiner.modState == Joiner.ModState.LISTED) {
                    if (!Version.same(joiner.hostVersion, Version.VERSION)) {
                        hostOlder = Version.compare(joiner.hostVersion, Version.VERSION) < 0;
                        phase = Phase.VERSION;
                        break;
                    }
                    hostMods = joiner.modList;
                    missing = missingMods(hostMods);
                    startOwnCheck();
                } else if (joiner.modState == Joiner.ModState.FAILED || System.currentTimeMillis() - checkStarted > 30000) {

                    OpenHouseMod.LOG.warn("couldn't get room {}'s mod list ({}), joining without it", code, joiner.modError);
                    connect();
                }
                break;
            case OWN: {

                List<ModShare.Mod> own = ownMissing;
                boolean late = System.currentTimeMillis() - ownStarted > 20000;
                if (own != null || late) {
                    if (own != null) missing = own;
                    else OpenHouseMod.LOG.info("reading this game's own mod jars is taking too long - going by mod versions alone");
                    packsWanted = packsToFetch(hostMods);
                    if (!packsWanted.isEmpty()) startPacks();
                    else afterPacks();
                }
                break;
            }
            case PACKS:
                if (joiner.modState == Joiner.ModState.DONE) {
                    placePacks();
                    if (packsGot.isEmpty()) afterPacks();
                    else { phase = Phase.PACKS_GOT; packsGotAt = System.currentTimeMillis(); }
                } else if (joiner.modState == Joiner.ModState.FAILED) {
                    OpenHouseMod.LOG.warn("couldn't get room {}'s texture packs ({}), joining without them", code, joiner.modError);
                    afterPacks();
                }
                break;
            case PACKS_GOT:
                if (System.currentTimeMillis() - packsGotAt > 3500) afterPacks();
                break;
            case DOWNLOAD:
                if (joiner.modState == Joiner.ModState.DONE) {
                    try {
                        File pack = packDir();

                        File self = Rtc.self();
                        if (!self.isFile()) throw new java.io.IOException("this game isn't running the mod from a jar (development run)");
                        Files.copy(self.toPath(), new File(pack, "openhouse-" + FabricLoader.getInstance().getModContainer("openhouse")
                                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("") + ".jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
                        joiner.downloadClose();
                        OpenHouseMod.rememberCode(code);
                        restarted = Relaunch.start(pack, code);
                        phase = Phase.RESTART;
                        restartAt = System.currentTimeMillis() + 4000;
                    } catch (Exception e) {
                        fail(e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                } else if (joiner.modState == Joiner.ModState.FAILED) {
                    fail(joiner.modError);
                }
                break;
            case RESTART:

                Process p = restarted;
                if (p != null && !p.isAlive()) {
                    restartAt = Long.MAX_VALUE;
                    fail(I18n.getInstance().translateKeyAndFormat("gui.openhouse.mods.restartfailed", p.exitValue()));
                } else if (System.currentTimeMillis() > restartAt) { restartAt = Long.MAX_VALUE; mc.shutdown(); }
                break;
            default:
        }
        layout();
    }
    private long restartAt = Long.MAX_VALUE;
    private Process restarted;

    private void afterPacks() {
        if (missing.isEmpty()) {
            if (!packsWanted.isEmpty()) joiner.downloadClose();
            connect();
        } else phase = Phase.ASK;
    }

    private static File texturePacksDir() {
        return new File(FabricLoader.getInstance().getGameDir().toFile(), "texturepacks");
    }

    private static String packName(ModShare.Mod m) {
        String n = (m.name == null || m.name.isEmpty() ? m.file : m.name).replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? n : n + ".zip";
    }

    private String packNameBeside(ModShare.Mod m) {
        String n = packName(m);
        return n.substring(0, n.length() - 4) + " (" + code + ").zip";
    }

    private static boolean sameFile(File f, ModShare.Mod m) {
        try { return f.isFile() && f.length() == m.size && m.sha256.equals(Rtc.sha256(f)); } catch (java.io.IOException e) { return false; }
    }

    private List<ModShare.Mod> packsToFetch(List<ModShare.Mod> host) {
        List<ModShare.Mod> out = new ArrayList<>();
        File dir = texturePacksDir();
        for (ModShare.Mod m : host) {
            if (!ModShare.TEXTURE_PACK.equals(m.id)) continue;
            if (sameFile(new File(dir, packName(m)), m) || sameFile(new File(dir, packNameBeside(m)), m)) continue;
            out.add(m);
        }
        return out;
    }

    private File incomingPacks() {
        return new File(OpenHouseMod.dataDir(), "incoming-packs");
    }

    private void startPacks() {
        File in = incomingPacks();
        File[] old = in.listFiles();
        if (old != null) for (File f : old) f.delete();
        if (!in.isDirectory() && !in.mkdirs()) { afterPacks(); return; }
        phase = Phase.PACKS;
        joiner.downloadMods(in, packsWanted);
    }

    private void placePacks() {
        File dir = texturePacksDir();
        dir.mkdirs();
        for (ModShare.Mod m : packsWanted) {
            File got = new File(incomingPacks(), m.file);
            if (!got.isFile()) continue;
            File to = new File(dir, packName(m));
            if (to.exists()) to = new File(dir, packNameBeside(m));
            try {
                Files.move(got.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
                packsGot.add(to.getName().substring(0, to.getName().length() - 4));
                OpenHouseMod.LOG.info("got texture pack {} from room {} (in texturepacks, not turned on)", to.getName(), code);
            } catch (java.io.IOException e) {
                OpenHouseMod.LOG.warn("couldn't put texture pack {} in texturepacks: {}", to.getName(), e.toString());
            }
        }
    }

    private void fail(String why) {
        problem = why == null ? "" : why;
        phase = Phase.FAILED;
    }

    private void connect() {
        phase = Phase.CONNECT;
        OpenHouseMod.rememberCode(code);

        if (getParentScreen() instanceof ScreenSelectServer) ((ScreenSelectServer) getParentScreen()).freeResources();
        OpenHouseMod.LOG.info("room {} found, connecting through 127.0.0.1:{}", code, joiner.port);
        offerOwnFolders();
        mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", joiner.port));
    }

    private void offerOwnFolders() {
        try {
            if (openhouse.ShareFolders.folders(OpenHouseMod.shareFoldersSetting()).isEmpty()) return;
            joiner.shareFolders(FabricLoader.getInstance().getGameDir().toFile(), OpenHouseMod.shareFoldersSetting());
        } catch (Throwable t) {
            OpenHouseMod.LOG.info("couldn't offer this game's shared folders to the room ({})", t.toString());
        }
    }

    private void startOwnCheck() {
        phase = Phase.OWN;
        ownStarted = System.currentTimeMillis();
        List<ModShare.Mod> host = hostMods;
        Thread t = new Thread(() -> {
            List<ModShare.Mod> out;
            try {
                out = reallyMissing(host, rebuilt);
            } catch (Throwable e) {
                OpenHouseMod.LOG.info("couldn't read this game's own mod jars ({}) - going by mod versions alone", e.toString());
                out = missingMods(host);
            }
            ownMissing = out;
        }, "openhouse own mods check");
        t.setDaemon(true);
        t.start();
    }

    static List<ModShare.Mod> reallyMissing(List<ModShare.Mod> host, Set<String> rebuilt) throws java.io.IOException {
        Map<String, String> have = new HashMap<>();
        Map<String, File> jarOf = new HashMap<>();
        File folder = LocalServer.modsFolder().getAbsoluteFile();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            String id = c.getMetadata().getId();
            have.put(id, c.getMetadata().getVersion().getFriendlyString());
            ModOrigin o;
            try { o = c.getOrigin(); } catch (Throwable t) { continue; }
            if (o.getKind() != ModOrigin.Kind.PATH) continue;
            for (java.nio.file.Path p : o.getPaths()) {
                File f = p.toFile().getAbsoluteFile();

                if (f.isFile() && f.getName().toLowerCase().endsWith(".jar") && folder.equals(f.getParentFile())) jarOf.put(id, f);
            }
        }
        Map<File, String> sha = new HashMap<>();
        Set<String> mine = new HashSet<>();
        File[] files = folder.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (files != null) for (File f : files) {
            String s = Rtc.sha256(f);
            sha.put(f.getAbsoluteFile(), s);
            mine.add(s);
        }
        List<ModShare.Mod> out = new ArrayList<>();
        for (ModShare.Mod m : host) {
            if (OpenHouseMod.MOD_ID.equals(m.id)) continue;
            if (m.id != null && m.id.startsWith("openhouse:")) continue;
            if (m.sha256 != null && mine.contains(m.sha256)) continue;
            if (m.version == null || !m.version.equals(have.get(m.id))) { out.add(m); continue; }
            File own = jarOf.get(m.id);
            String ours = own == null ? null : sha.get(own.getAbsoluteFile());
            if (ours == null || ours.equals(m.sha256)) continue;
            out.add(m);
            if (m.sha256 != null) rebuilt.add(m.sha256);
            OpenHouseMod.LOG.info("{} {} is here as {}, but the host's is a different build - getting theirs", m.id, m.version, own.getName());
        }
        return out;
    }

    static List<ModShare.Mod> missingMods(List<ModShare.Mod> host) {
        Map<String, String> have = new HashMap<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods())
            have.put(c.getMetadata().getId(), c.getMetadata().getVersion().getFriendlyString());
        List<ModShare.Mod> out = new ArrayList<>();
        for (ModShare.Mod m : host) {
            if (OpenHouseMod.MOD_ID.equals(m.id)) continue;
            if (m.id != null && m.id.startsWith("openhouse:")) continue;
            if (m.version == null || !m.version.equals(have.get(m.id))) out.add(m);
        }
        return out;
    }

    private File packDir() {
        return new File(new File(OpenHouseMod.dataDir(), "packs"), code);
    }

    private void startDownload() {
        try {
            File pack = packDir();
            if (!pack.isDirectory() && !pack.mkdirs()) throw new java.io.IOException("can't create " + pack);
            Set<String> keep = new HashSet<>();
            List<ModShare.Mod> fetch = new ArrayList<>();
            for (ModShare.Mod m : hostMods) {
                if (OpenHouseMod.MOD_ID.equals(m.id) || ModShare.TEXTURE_PACK.equals(m.id)) continue;
                keep.add(m.file);
                File have = new File(pack, m.file);
                if (!(have.isFile() && have.length() == m.size && m.sha256.equals(Rtc.sha256(have)))) fetch.add(m);
            }
            File[] old = pack.listFiles();
            if (old != null) for (File f : old) if (f.isFile() && !keep.contains(f.getName())) f.delete();
            phase = Phase.DOWNLOAD;
            joiner.downloadMods(pack, fetch);
        } catch (Exception e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @Override protected void buttonClicked(@NotNull ButtonElement button) {
        if (button == back) {
            if (phase == Phase.DOWNLOAD) joiner.downloadClose();
            mc.displayScreen(getParentScreen());
        } else if (button == retry) {
            if (joiner.alive()) joiner.probe();
            else mc.displayScreen(new ScreenRoomJoin(getParentScreen(), code));
        } else if (button == download) {
            startDownload();
        } else if (button == anyway) {
            connect();
        } else if (button == update) {
            mc.displayScreen(new ScreenUpdate(getParentScreen(), null, code));
        }
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE && back.enabled) buttonClicked(back);
    }

    @Override public void render(int mx, int my, float partialTick) {
        renderBackground();
        I18n t = I18n.getInstance();
        int cx = width / 2, y = height / 4 - 10;
        drawStringCenteredShadow(fontRenderer, t.translateKeyAndFormat("gui.openhouse.join.title", code), cx, y, 0xFFFFFF);
        List<String> lines = new ArrayList<>();
        int color = 0xA0A0A0;
        switch (phase) {
            case LOOKUP:
                switch (joiner.state) {
                    case STARTING: lines.add(joiner.detail.isEmpty() ? t.translateKey("gui.openhouse.join.starting") : joiner.detail); break;
                    case SEARCHING: lines.add(joiner.offline ? t.translateKey("gui.openhouse.join.offline") : t.translateKey("gui.openhouse.join.searching")); break;
                    case READY: lines.add(t.translateKey("gui.openhouse.join.connecting")); break;
                    case NOT_FOUND: lines.add(t.translateKey("gui.openhouse.join.notfound")); lines.add(t.translateKey("gui.openhouse.join.notfound2")); color = 0xFF6060; break;
                    case NO_ANSWER: lines.add(t.translateKey("gui.openhouse.join.noanswer")); color = 0xFF6060; break;
                    case FAILED: lines.add(t.translateKey("gui.openhouse.join.failed")); lines.add(joiner.detail); color = 0xFF6060; break;
                    default:
                }
                break;
            case CHECK:
                lines.add(t.translateKey("gui.openhouse.mods.checking"));
                break;
            case OWN:
                lines.add(t.translateKey("gui.openhouse.mods.checkingown"));
                break;
            case VERSION: {
                String hv = Version.base(joiner.hostVersion);
                lines.add(hv == null ? t.translateKeyAndFormat("gui.openhouse.version.older", Version.VERSION)
                        : t.translateKeyAndFormat("gui.openhouse.version.different", hv, Version.VERSION));
                lines.add(t.translateKey(hostOlder ? "gui.openhouse.version.hostupdate" : "gui.openhouse.version.youupdate"));
                color = 0xFFFFFF;
                break;
            }
            case ASK: {
                long bytes = 0;
                for (ModShare.Mod m : missing) bytes += Math.max(0, m.size);
                lines.add(t.translateKeyAndFormat("gui.openhouse.mods.missing", missing.size(), mb(bytes)));
                int shown = 0;
                for (ModShare.Mod m : missing) {
                    if (shown++ == 4 && missing.size() > 5) { lines.add(t.translateKeyAndFormat("gui.openhouse.mods.more", missing.size() - 4)); break; }
                    lines.add("§7" + m.name + " " + m.version
                            + (rebuilt.contains(m.sha256) ? " §8(" + t.translateKey("gui.openhouse.mods.differentbuild") + ")" : ""));
                }
                lines.add("§6" + t.translateKey("gui.openhouse.mods.trust"));
                color = 0xFFFFFF;
                break;
            }
            case PACKS: {
                long total = Math.max(1, joiner.modBytesTotal);
                lines.add(t.translateKeyAndFormat("gui.openhouse.packs.downloading", (int) (joiner.modBytesDone * 100 / total), mb(joiner.modBytesTotal)));
                break;
            }
            case PACKS_GOT:
                lines.add(t.translateKeyAndFormat("gui.openhouse.packs.got", String.join(", ", packsGot)));
                lines.add(t.translateKey(packsGot.size() == 1 ? "gui.openhouse.packs.where" : "gui.openhouse.packs.where2"));
                color = 0xFFFFFF;
                break;
            case DOWNLOAD: {
                long total = Math.max(1, joiner.modBytesTotal);
                lines.add(t.translateKeyAndFormat("gui.openhouse.mods.downloading", (int) (joiner.modBytesDone * 100 / total), mb(joiner.modBytesTotal)));
                break;
            }
            case RESTART:
                lines.add(t.translateKey("gui.openhouse.mods.restarting"));
                break;
            case FAILED:
                lines.add(t.translateKey("gui.openhouse.mods.failed"));
                lines.add(problem);
                color = 0xFF6060;
                break;
            default:
        }
        int ly = y + 22;
        for (String l : lines) {
            drawStringCenteredShadow(fontRenderer, l, cx, ly, color);
            ly += 11;
        }
        super.render(mx, my, partialTick);
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
