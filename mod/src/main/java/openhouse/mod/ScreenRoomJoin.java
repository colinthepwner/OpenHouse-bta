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
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;
import net.minecraft.client.gui.server.ScreenSelectServer;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenRoomJoin extends Screen {
    private enum Phase { LOOKUP, CHECK, VERSION, ASK, DOWNLOAD, RESTART, FAILED, CONNECT }

    private final String code;
    private final Joiner joiner;
    private Phase phase = Phase.LOOKUP;
    private List<ModShare.Mod> hostMods = new ArrayList<>(), missing = new ArrayList<>();
    private String problem = "";
    private ButtonElement back, retry, download, anyway, update;
    private boolean hostOlder;
    private long checkStarted;

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
                    if (missing.isEmpty()) connect();
                    else phase = Phase.ASK;
                } else if (joiner.modState == Joiner.ModState.FAILED || System.currentTimeMillis() - checkStarted > 30000) {

                    OpenHouseMod.LOG.warn("couldn't get room {}'s mod list ({}), joining without it", code, joiner.modError);
                    connect();
                }
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

    private void fail(String why) {
        problem = why == null ? "" : why;
        phase = Phase.FAILED;
    }

    private void connect() {
        phase = Phase.CONNECT;
        OpenHouseMod.rememberCode(code);

        if (getParentScreen() instanceof ScreenSelectServer) ((ScreenSelectServer) getParentScreen()).freeResources();
        OpenHouseMod.LOG.info("room {} found, connecting through 127.0.0.1:{}", code, joiner.port);
        mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", joiner.port));
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
                if (OpenHouseMod.MOD_ID.equals(m.id)) continue;
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
                    lines.add("§7" + m.name + " " + m.version);
                }
                lines.add("§6" + t.translateKey("gui.openhouse.mods.trust"));
                color = 0xFFFFFF;
                break;
            }
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
