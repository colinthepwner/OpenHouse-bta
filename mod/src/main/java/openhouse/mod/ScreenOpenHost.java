package openhouse.mod;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;
import openhouse.OpenHostList;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenOpenHost extends Screen {
    enum Mode { MOVE, PLAY, BACK }

    private final Mode mode;
    private final String world, display;
    private final OpenHostList.Server server;
    private ButtonElement go, cancel;
    private volatile String status, error;
    private volatile boolean busy, done;
    private long waitFrom;
    private Process started;

    private ScreenOpenHost(Screen back, Mode mode, String world, String display, OpenHostList.Server server) {
        super(back);
        this.mode = mode; this.world = world; this.display = display; this.server = server;
    }

    public static ScreenOpenHost move(Screen back, String world, String display) { return new ScreenOpenHost(back, Mode.MOVE, world, display, null); }

    public static ScreenOpenHost play(Screen back, OpenHostList.Server s) { return new ScreenOpenHost(back, Mode.PLAY, s.level, OpenHostWorlds.nameOf(s), s); }
    public static ScreenOpenHost back(Screen back, OpenHostList.Server s) { return new ScreenOpenHost(back, Mode.BACK, s.level, OpenHostWorlds.nameOf(s), s); }

    @Override public void init() {
        buttons.clear();
        String yes = mode == Mode.MOVE ? "Move to OpenHost" : mode == Mode.BACK ? (moved() ? "Move Back" : "Copy Here") : "";
        go = add(new ButtonElement(1, width / 2 - 154, height / 4 + 132, 150, 20, yes));
        cancel = add(new ButtonElement(0, width / 2 + 4, height / 4 + 132, 150, 20, "Cancel"));
        if (mode == Mode.PLAY) { go.visible = false; cancel.xPosition = width / 2 - 75; if (!busy && !done) startPlay(); }
        if (mode == Mode.MOVE) {
            String why = MoveToOpenHost.blocked();
            if (why != null) { error = why; go.enabled = false; }
        }
        if (mode == Mode.BACK && MoveToOpenHost.running(server, false)) { error = "Its OpenHost server is running. Stop it first (Stop in the OpenHost window)."; go.enabled = false; }
    }

    private boolean moved() { return server != null && new File(server.folder, MoveToOpenHost.MOVED).isFile(); }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (b == cancel) {
            if (busy && mode != Mode.PLAY) return;
            mc.displayScreen(getParentScreen());
            return;
        }
        if (b == go && mode == Mode.PLAY && done) { { OpenHostWorlds.playing = server; mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", server.port)); } return; }
        if (b != go || busy) return;
        if (done) { mc.displayScreen(getParentScreen()); return; }
        busy = true;
        go.enabled = false;
        cancel.enabled = false;
        Thread t = new Thread(() -> {
            try {
                if (mode == Mode.MOVE) {

                    next = MoveToOpenHost.move(world, x -> status = x);
                    status = null;
                    done = true;
                } else if (mode == Mode.BACK) {
                    boolean mv = moved();
                    status = mv ? "Moving the world back..." : "Copying the world...";
                    String name = MoveBack.run(server, mv);
                    status = (mv ? "Moved back. " : "Copied. ") + "It's a single player world again: " + name;
                    done = true;
                    busy = false;
                    go.displayString = "Done";
                    go.enabled = true;
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                CrashReports.problem(mode == Mode.MOVE ? "Move to OpenHost failed" : "Moving a world back from OpenHost failed", error, null);
                busy = false;
                cancel.enabled = true;
            }
        }, "openhouse openhost move");
        t.setDaemon(true);
        t.start();
    }

    private volatile ModSync.Result sync;
    private boolean restarting;

    private void startPlay() {
        busy = true;
        waitFrom = System.currentTimeMillis();
        boolean up = MoveToOpenHost.running(server, true);

        if (moved()) {
            try {
                sync = ModSync.run(server.folder, CrashReports.mods());
                if (up && sync.changed()) {
                    java.nio.file.Files.write(new File(server.folder, "openhouse/control.txt").toPath(), "restart".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    restarting = true;
                    status = "Restarting the server with your mods...";
                    return;
                }
            } catch (Exception e) {
                OpenHouseMod.LOG.warn("couldn't bring the server's mods up to date: {}", e.toString());
            }
        }
        if (up) return;

        if (MoveToOpenHost.running(server, false)) {
            if ("stopping".equals(MoveToOpenHost.state(server))) { waitStop = true; status = "Waiting for the server to finish stopping..."; }
            else status = "Starting OpenHost...";
            return;
        }
        launch();
    }

    private boolean waitStop;

    private void launch() {
        status = "Starting OpenHost...";
        try { started = MoveToOpenHost.start(server.folder); }
        catch (Exception e) { error = e.getMessage(); busy = false; }
    }

    private volatile OpenHostList.Server next;

    @Override public void tick() {
        OpenHostList.Server n = next;
        if (n != null) { next = null; mc.displayScreen(play(getParentScreen(), n)); return; }
        if (mode != Mode.PLAY || !busy || error != null) return;
        long t = System.currentTimeMillis() - waitFrom;
        if (t % 1000 < 60 || t < 100) {
            if (waitStop) {
                if (!MoveToOpenHost.running(server, false)) { waitStop = false; launch(); }
                return;
            }

            if (restarting) {
                if (!MoveToOpenHost.running(server, true)) restarting = false;
                else if (t > 60000) { error = "The server didn't restart. Restart it from its window, then join."; busy = false; }
                return;
            }
            if (MoveToOpenHost.running(server, true)) {
                busy = false;
                done = true;

                ModSync.Result r = sync;
                if (r != null && !r.kept.isEmpty()) { go.visible = true; go.enabled = true; go.displayString = "Join Server"; status = null; return; }
                { OpenHostWorlds.playing = server; mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", server.port)); }
                return;
            }
            if (started != null && !started.isAlive()) { error = "OpenHost closed. Its window or openhouse/started-by-game.log in the server folder says why."; busy = false; }
            else if (t > 30000) status = "Starting OpenHost... (its window shows how it's going)";
        }
        if (t > 300000) { error = "OpenHost didn't open its server in 5 minutes. Its window says why."; busy = false; }
    }

    static String names(List<String> jars) {
        List<String> n = new ArrayList<>();
        for (String j : jars) n.add(j.replaceAll("(?i)\\.jar$", ""));
        String s = String.join(", ", n);
        return s.length() > 70 ? s.substring(0, 67) + "..." : s;
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE) buttonClicked(cancel);
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        int cx = width / 2, y = height / 4 - 4;
        List<String[]> lines = new ArrayList<>();
        String title;
        if (mode == Mode.MOVE) {
            title = "Move \"" + display + "\" to OpenHost";
            lines.add(new String[] { "OpenHost runs this world as its own server:", "FFFFFF" });
            lines.add(new String[] { "more than 9 players, and friends can play while your game is closed.", "FFFFFF" });
            lines.add(new String[] { "", "" });
            lines.add(new String[] { "The world MOVES to the server. It won't be a single player world", "FFD080" });
            lines.add(new String[] { "any more: it shows in this list in orange, and clicking it joins the server.", "FFD080" });
            lines.add(new String[] { "", "" });
            lines.add(new String[] { "Your mods, password and whitelist go with it. A backup is made first,", "A0A0A0" });
            lines.add(new String[] { "and Move Back brings it back to single player any time.", "A0A0A0" });
        } else if (mode == Mode.BACK) {
            title = (moved() ? "Move \"" : "Copy \"") + display + (moved() ? "\" back to single player" : "\" to single player");
            if (moved()) {
                lines.add(new String[] { "The world leaves its OpenHost server and becomes a normal world", "FFFFFF" });
                lines.add(new String[] { "in this list again. Friends join through Open to Friends (up to 9).", "FFFFFF" });
                lines.add(new String[] { "", "" });
                lines.add(new String[] { "The server folder stays, without the world.", "A0A0A0" });
            } else {
                lines.add(new String[] { "This server was made on OpenHost, so its world stays there.", "FFFFFF" });
                lines.add(new String[] { "A COPY comes to your single player worlds.", "FFD080" });
                lines.add(new String[] { "", "" });
                lines.add(new String[] { "Changes in the copy don't reach the server, and the other way round.", "A0A0A0" });
            }
        } else {
            title = "\"" + display + "\" (World hosted using OpenHost)";
            lines.add(new String[] { done || !busy ? "Joining its server..." : "Starting its server. OpenHost opens on your taskbar.", "FFFFFF" });
            if (busy) lines.add(new String[] { "It stays up while anyone is on, and stops once everyone has left.", "A0A0A0" });
            ModSync.Result r = sync;
            if (r != null) {
                if (!r.added.isEmpty()) lines.add(new String[] { "New mods for the server: " + names(r.added), "80FF80" });
                if (!r.removed.isEmpty()) lines.add(new String[] { "Taken off the server: " + names(r.removed), "C0C0C0" });
                if (!r.kept.isEmpty()) {
                    lines.add(new String[] { "Kept on the server: " + names(r.kept), "FFD080" });
                    lines.add(new String[] { "Your world already has their things. Put them back in your mods folder to see them.", "A0A0A0" });
                }
            }
        }
        drawStringCenteredShadow(fontRenderer, title, cx, y, 0xFFB43C);
        y += 20;
        for (String[] l : lines) {
            if (!l[0].isEmpty()) drawStringCenteredShadow(fontRenderer, l[0], cx, y, Integer.parseInt(l[1], 16));
            y += l[0].isEmpty() ? 6 : 11;
        }
        String s = status, e = error;
        if (s != null) drawStringCenteredShadow(fontRenderer, (busy ? s.replaceAll("\\.+$", "") + "...".substring(0, (int) (System.currentTimeMillis() / 400 % 4)) : s), cx, height / 4 + 112, 0xE0E0E0);
        if (e != null) drawStringCenteredShadow(fontRenderer, e, cx, height / 4 + 112, 0xFF8080);
        super.render(mx, my, pt);
    }
}
