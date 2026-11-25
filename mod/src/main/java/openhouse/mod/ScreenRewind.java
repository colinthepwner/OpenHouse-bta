package openhouse.mod;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.player.controller.PlayerControllerSP;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.client.render.texture.Texture;
import net.minecraft.client.render.texture.TextureBuffered;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenRewind extends Screen {
    private final String dir, name;
    private final File world;
    private List<File> snaps = new ArrayList<>();

    private final Map<File, BufferedImage> loaded = new ConcurrentHashMap<>();
    private final Map<File, Texture> tex = new ConcurrentHashMap<>();

    private int sel = -1, first = 0;
    private volatile boolean working, ready;
    private volatile String status, error;
    private int drag_x = Integer.MIN_VALUE, drag_first;

    public static volatile String pending_error;

    private ButtonElement go, back, left, right;

    public static Screen leaveVisit(boolean replace) {
        String of = WorldGuard.visit_of, name = WorldGuard.visit_name;
        Screen list = new net.minecraft.client.gui.ScreenSelectWorld(new net.minecraft.client.gui.ScreenMainMenu());
        if (of == null) return list;
        try {
            WorldGuard.endVisit(WorldGuard.saveDir(of), replace);
        } catch (Throwable e) {
            OpenHouseMod.LOG.warn("leaving the timeline save of {} went wrong: {}", of, e.toString());

            pending_error = e.getMessage() == null ? e.toString() : e.getMessage();
            replace = false;
        }
        return replace ? list : new ScreenRewind(list, of, name);
    }

    public static void leave(net.minecraft.client.Minecraft mc, boolean replace) {
        if (!WorldGuard.visiting()) return;
        mc.statsCounter.add(net.minecraft.core.achievement.stat.StatList.leaveGameStat, 1);
        if (!mc.isMultiplayerWorld()) {
            mc.changeWorld(null);
            mc.displayScreen(leaveVisit(replace));
        } else if (LocalServer.hosting()) {
            leave_replace = replace;
            mc.currentWorld.sendQuittingDisconnectingPacket();
            mc.changeWorld(null);
            LocalServer ls = LocalServer.current();
            ls.stop();
            mc.displayScreen(new ScreenOpenToFriends(ls, true));
        }
    }

    public static volatile Boolean leave_replace;

    public static Screen afterHosting(LocalServer ls) {
        if (ls == null || !WorldGuard.isVisit(ls.worldDir) || WorldGuard.visit_of == null) return new net.minecraft.client.gui.ScreenMainMenu();
        Boolean r = leave_replace;
        leave_replace = null;
        boolean stopped = ls.state == LocalServer.State.STOPPED || ls.state == LocalServer.State.FAILED;
        if (r == null || !stopped) return new ScreenVisitEnd(ls);
        return leaveVisit(r);
    }

    static final int GAP = 8;

    private int CW = 128, CH = 72, PITCH = CW + GAP;

    private final openhouse.OpenHostList.Server server;

    public ScreenRewind(Screen parent, openhouse.OpenHostList.Server s, String name) {
        super(parent);
        this.server = s;
        this.dir = null;
        this.name = name == null || name.isEmpty() ? s.level : name;
        this.world = s.world();
        List<File> t = new ArrayList<>(WorldGuard.timeline(world));
        Collections.reverse(t);
        snaps = t;
        sel = snaps.size();
        loadPictures();
    }

    private void loadPictures() {
        Thread l = new Thread(() -> {
            for (int i = snaps.size() - 1; i >= 0; i--) {
                File f = new File(snaps.get(i), WorldGuard.THUMB);
                try { if (f.isFile()) { BufferedImage img = ImageIO.read(f); if (img != null) loaded.put(snaps.get(i), img); } } catch (Exception ignored) { }
            }
        }, "OpenHouse rewind pictures");
        l.setDaemon(true);
        l.start();
    }

    public ScreenRewind(Screen parent, String dir, String name) {
        super(parent);
        this.server = null;
        this.dir = dir;
        this.name = name == null || name.isEmpty() ? dir : name;
        this.world = WorldGuard.saveDir(dir);
        List<File> t = new ArrayList<>(WorldGuard.timeline(world));
        Collections.reverse(t);
        snaps = t;
        sel = snaps.size();
        error = pending_error;
        pending_error = null;
        Thread l = new Thread(() -> {
            for (int i = snaps.size() - 1; i >= 0; i--) {
                File f = new File(snaps.get(i), WorldGuard.THUMB);
                try { if (f.isFile()) { BufferedImage img = ImageIO.read(f); if (img != null) loaded.put(snaps.get(i), img); } } catch (Exception ignored) { }
            }
        }, "OpenHouse rewind pictures");
        l.setDaemon(true);
        l.start();
    }

    private int fit() { return Math.max(1, (width - 40 + GAP) / PITCH); }
    private int total() { return snaps.size() + 1; }
    private int maxFirst() { return Math.max(0, total() - fit()); }
    private int stripX() { int n = Math.min(fit(), total()); return width / 2 - (n * PITCH - GAP) / 2; }
    private int stripY() { return height / 2 - CH / 2 - 14; }
    private boolean now() { return sel == snaps.size(); }

    @Override public void init() {
        CW = Math.max(72, Math.min(128, (width - 40) / 4 - GAP));
        CH = CW * 9 / 16;
        PITCH = CW + GAP;
        buttons.clear();
        go = add(new ButtonElement(1, width / 2 - 154, height - 28, 150, 20, ""));
        back = add(new ButtonElement(0, width / 2 + 4, height - 28, 150, 20, I18n.getInstance().translateKey("gui.openhouse.button.back")));
        left = add(new ButtonElement(2, 4, stripY() + CH / 2 - 10, 14, 20, "<"));
        right = add(new ButtonElement(3, width - 18, stripY() + CH / 2 - 10, 14, 20, ">"));
        show(sel);
        sync();
    }

    private void show(int i) {
        if (i < first) first = i;
        if (i >= first + fit()) first = i - fit() + 1;
        first = Math.max(0, Math.min(maxFirst(), first));
    }

    private void sync() {
        go.displayString = server != null ? (now() ? "Join Server" : "Restore This Save")
                : I18n.getInstance().translateKey(now() ? "gui.openhouse.rewind.button.play" : "gui.openhouse.rewind.button.load");
        go.enabled = !working && sel >= 0 && sel <= snaps.size();
        back.enabled = !working;
        left.visible = right.visible = !working;
        left.enabled = first > 0;
        right.enabled = first < maxFirst();
    }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (!b.enabled) return;
        if (b == back) mc.displayScreen(getParentScreen());
        else if (b == go && server != null) { if (now()) mc.displayScreen(ScreenOpenHost.play(getParentScreen(), server)); else restore(); }
        else if (b == go) { if (now()) play(dir); else load(); }
        else if (b == left) { first = Math.max(0, first - 1); sync(); }
        else if (b == right) { first = Math.min(maxFirst(), first + 1); sync(); }
    }

    private void play(String folder) {
        mc.displayScreen(null);
        mc.playerController = new PlayerControllerSP(mc);
        mc.startWorld(folder);
        mc.displayScreen(null);
    }

    private void restore() {
        if (MoveToOpenHost.running(server, false)) { error = "Its OpenHost server is running. Stop it first (Stop in the OpenHost window)."; return; }
        File from = snaps.get(sel);
        working = true;
        error = null;
        status = "Restoring...";
        sync();
        Thread t = new Thread(() -> {
            try {
                WorldGuard.startVisit(world, from, (what, pct) -> status = "Restoring... " + pct + "%");
                WorldGuard.endVisit(world, true);
                openhouse.OhCrash.note("restored OpenHost world " + server.level + " from " + from.getName());
                status = "Restored. Join Server plays it.";
                List<File> l = new ArrayList<>(WorldGuard.timeline(world));
                Collections.reverse(l);
                snaps = l;
                sel = snaps.size();
            } catch (Throwable e) {
                OpenHouseMod.LOG.warn("couldn't restore {} from the timeline: {}", server.level, e.toString());
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                status = null;
            } finally {
                working = false;
            }
        }, "OpenHouse timeline restore");
        t.start();
    }

    private void load() {
        File from = snaps.get(sel);
        working = true;
        error = null;
        status = I18n.getInstance().translateKey("gui.openhouse.rewind.loading");
        sync();
        Thread t = new Thread(() -> {
            try {
                WorldGuard.startVisit(world, from, (what, pct) -> status = I18n.getInstance().translateKeyAndFormat("gui.openhouse.rewind.loading.pct", pct));
                ready = true;
            } catch (Throwable e) {
                OpenHouseMod.LOG.warn("couldn't load {} from the timeline: {}", dir, e.toString());
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                working = false;
            }
        }, "OpenHouse timeline load");
        t.start();
    }

    @Override public void tick() {
        if (ready) {
            ready = false;
            WorldGuard.visit_of = dir;
            WorldGuard.visit_name = name;
            play(WorldGuard.visitDir(dir));
            return;
        }
        sync();
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (working) return;
        if (key == Keyboard.KEY_ESCAPE) { buttonClicked(back); return; }
        if (key == Keyboard.KEY_LEFT && sel > 0) { sel--; show(sel); sync(); }
        else if (key == Keyboard.KEY_RIGHT && sel < snaps.size()) { sel++; show(sel); sync(); }
        else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) buttonClicked(go);
    }

    private int cardAt(int mx, int my) {
        int x0 = stripX(), y0 = stripY();
        if (my < y0 || my >= y0 + CH + 36 || mx < x0) return -1;
        int k = (mx - x0) / PITCH;
        if ((mx - x0) % PITCH >= CW || k >= fit()) return -1;
        int i = first + k;
        return i < total() ? i : -1;
    }

    @Override public void mouseClicked(int mx, int my, int btn) {
        if (btn == 0 && !working) {
            int i = cardAt(mx, my);
            if (i >= 0) {
                sel = i;
                sync();
                mc.sndManager.playSound("random.click", net.minecraft.core.sound.SoundCategory.GUI_SOUNDS, 1.0F, 1.0F);
                drag_x = mx; drag_first = first;
                return;
            }
            if (my >= stripY() && my < stripY() + CH + 36) { drag_x = mx; drag_first = first; }
        }
        super.mouseClicked(mx, my, btn);
    }

    @Override public void removed() {
        for (Texture t : tex.values()) { try { t.delete(); } catch (Throwable ignored) { } }
        tex.clear();
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        I18n t = I18n.getInstance();
        int cx = width / 2;
        drawStringCenteredShadow(fontRenderer, t.translateKeyAndFormat("gui.openhouse.rewind.title", name), cx, 20, 0xFFFFFF);

        if (!working) {
            int dw = org.lwjgl.input.Mouse.getDWheel();
            if (dw != 0) { first = Math.max(0, Math.min(maxFirst(), first - Integer.signum(dw))); sync(); }
            if (drag_x != Integer.MIN_VALUE) {
                if (!org.lwjgl.input.Mouse.isButtonDown(0)) drag_x = Integer.MIN_VALUE;
                else { first = Math.max(0, Math.min(maxFirst(), drag_first - Math.round((mx - drag_x) / (float) PITCH))); sync(); }
            }
        }

        String sub = working ? status : error != null ? t.translateKeyAndFormat("gui.openhouse.rewind.failed", error)
                : snaps.isEmpty() ? t.translateKey("gui.openhouse.rewind.none") : t.translateKey("gui.openhouse.rewind.pick");
        drawStringCenteredShadow(fontRenderer, sub, cx, 34, error != null ? 0xFF6060 : 0xA0A0A0);
        if (!working && error == null && !snaps.isEmpty()) drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.rewind.pick.safe"), cx, 46, 0x707070);

        int x0 = stripX(), y0 = stripY(), n = Math.min(fit(), total() - first);

        int ly = y0 + CH + 40;
        drawRect(x0, ly, x0 + n * PITCH - GAP, ly + 1, 0x80FFFFFF);
        int hover = working ? -1 : cardAt(mx, my);
        for (int k = 0; k < n; k++) {
            int i = first + k, x = x0 + k * PITCH;
            boolean now = i == snaps.size();
            File b = now ? null : snaps.get(i);
            boolean on = i == sel;
            int frame = on ? 0xFFFFFFFF : i == hover ? 0xFFA0A0A0 : 0xFF404040;
            drawRect(x - 1, y0 - 1, x + CW + 1, y0 + CH + 1, frame);
            drawRect(x, y0, x + CW, y0 + CH, 0xFF1A1A1A);

            Texture tx = b == null ? (snaps.isEmpty() ? null : texture(snaps.get(snaps.size() - 1))) : texture(b);
            if (tx != null) {
                tx.bind();
                TessellatorGeneral ts = GLRenderer.getTessellator();
                ts.startDrawingQuads();
                ts.drawRectangleWithUV(x, y0, CW, CH, 0.0, 0.0, 1.0, 1.0);
                ts.draw();
            } else if (!now) {
                drawStringCenteredShadow(fontRenderer, "?", x + CW / 2, y0 + CH / 2 - 4, 0x606060);
            }
            if (now) {
                drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.rewind.now"), x + CW / 2, y0 + CH + 4, on ? 0xFFFFA0 : 0xE0E0E0);
                drawStringCenteredShadow(fontRenderer, fit(t.translateKey("gui.openhouse.rewind.now.sub"), CW), x + CW / 2, y0 + CH + 15, 0xA0A0A0);
            } else {
                drawStringCenteredShadow(fontRenderer, fit(why(b), CW), x + CW / 2, y0 + CH + 4, on ? 0xFFFFA0 : 0xE0E0E0);
                drawStringCenteredShadow(fontRenderer, fit(ago(b), CW), x + CW / 2, y0 + CH + 15, 0xA0A0A0);
                drawStringCenteredShadow(fontRenderer, fit(date(b), CW), x + CW / 2, y0 + CH + 26, 0x707070);
            }
            int dc = on ? 0xFFFFFFA0 : now ? 0xFF60C060 : 0xFFA0A0A0;
            drawRect(x + CW / 2 - 2, ly - 2, x + CW / 2 + 2, ly + 3, dc);
        }
        if (!working) drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.rewind.hint"), cx, ly + 10, 0x606060);
        super.render(mx, my, pt);
    }

    private String fit(String s, int w) {
        if (fontRenderer.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fontRenderer.stringWidth(s + "..") > w) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private Texture texture(File b) {
        Texture x = tex.get(b);
        if (x != null) return x;
        BufferedImage img = loaded.remove(b);
        if (img == null) return null;
        try { x = new TextureBuffered(img, true, true, false); tex.put(b, x); return x; } catch (Throwable e) { return null; }
    }

    static String why(File b) {
        String w = WorldGuard.why(b);
        String k = "gui.openhouse.rewind.why." + w;
        String s = I18n.getInstance().translateKey(k);
        return s == null || s.equals(k) ? w : s;
    }

    static String date(File b) { return new SimpleDateFormat("MMM d, HH:mm").format(new Date(WorldGuard.made(b))); }

    static String ago(File b) {
        long ago = System.currentTimeMillis() - WorldGuard.made(b);
        I18n t = I18n.getInstance();
        if (ago < 60_000L) return t.translateKey("gui.openhouse.rewind.ago.now");
        if (ago < 3600_000L) return t.translateKeyAndFormat("gui.openhouse.rewind.ago.min", ago / 60_000L);
        if (ago < 48 * 3600_000L) return t.translateKeyAndFormat("gui.openhouse.rewind.ago.hour", ago / 3600_000L);
        return t.translateKeyAndFormat("gui.openhouse.rewind.ago.day", ago / (24 * 3600_000L));
    }
}
