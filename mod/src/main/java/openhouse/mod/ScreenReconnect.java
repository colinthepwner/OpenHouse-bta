package openhouse.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenReconnect extends Screen {
    static final long FOR = 60_000L, GAP = 2_500L;

    static volatile String host, room;
    static volatile int port;
    static volatile long inWorldAt;

    static long startedAt, nextAt;
    static int tries;
    static String why;

    private ButtonElement cancel;

    ScreenReconnect(Screen back) { super(back); }

    static Object lastFailed;
    static void watch(Minecraft mc) {
        if (mc.currentWorld != null && mc.isMultiplayerWorld()) {
            inWorldAt = System.currentTimeMillis();
            host = OpenHouseMod.connectHost; port = OpenHouseMod.connectPort; room = OpenHouseMod.connectRoom;
            startedAt = 0;
            return;
        }
        Screen s = mc.currentScreen;
        if (!(s instanceof net.minecraft.client.gui.ScreenConnectFailed) || s == lastFailed) return;
        lastFailed = s;
        String[] w = OpenHouseMod.disconnectReason(s);
        if (w == null || host == null || !dropped(w)) return;
        long now = System.currentTimeMillis();
        boolean fresh = now - inWorldAt < 15_000;
        boolean retrying = startedAt > 0 && now - startedAt < FOR;
        if (!fresh && !retrying) return;

        if (LocalServer.hostToken(host, port) != null) return;
        LocalServer ls = LocalServer.current();
        if (ls != null && ls.crash != null) return;
        if (startedAt == 0) { startedAt = now; tries = 0; openhouse.OhCrash.note("connection lost (" + w[1] + "), reconnecting to " + (room != null ? "room " + room : host + ":" + port)); }
        why = w[1];
        nextAt = now + GAP;
        mc.displayScreen(new ScreenReconnect(null));
    }

    static boolean dropped(String[] w) {
        String a = w[0] == null ? "" : w[0].toLowerCase(java.util.Locale.ROOT), b = w[1] == null ? "" : w[1].toLowerCase(java.util.Locale.ROOT);
        if (a.contains("disconnected by server") || a.contains("kicked")) return false;
        return a.contains("connection lost") || a.contains("connection failed") || b.contains("end of stream") || b.contains("timed out")
                || b.contains("internal exception") || b.contains("connection reset") || b.contains("broken pipe") || b.contains("connection refused");
    }

    @Override public void init() {
        buttons.clear();
        cancel = add(new ButtonElement(0, width / 2 - 100, height / 4 + 120, 200, 20, "Cancel"));
    }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (b == cancel) { startedAt = 0; host = null; mc.displayScreen(new net.minecraft.client.gui.ScreenMainMenu()); }
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE) buttonClicked(cancel);
    }

    @Override public void tick() {
        long now = System.currentTimeMillis();
        if (now - startedAt > FOR) {
            startedAt = 0;
            openhouse.OhCrash.note("gave up reconnecting after " + tries + " tries");
            CrashReports.problem("Couldn't get back in after a lost connection (" + (room != null ? "room " + room : "a server") + ")", why, null);
            mc.displayScreen(new net.minecraft.client.gui.ScreenMainMenu());
            return;
        }
        if (now < nextAt) return;
        nextAt = now + GAP;
        tries++;
        String h = host;
        int p = port;

        if (room != null) {
            openhouse.Joiner j = OpenHouseMod.joinerNow();
            if (j == null || !j.alive() || !room.equals(j.code)) j = OpenHouseMod.joinerFor(room);
            if (j.state != openhouse.Joiner.State.READY || j.port <= 0) return;
            h = "127.0.0.1"; p = j.port;
        }
        if (h == null) return;
        mc.displayScreen(new ScreenConnecting(mc, h, p));
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        int cx = width / 2, y = height / 4 + 20;
        drawStringCenteredShadow(fontRenderer, "Connection lost", cx, y, 0xFFFFFF);
        String dots = "...".substring(0, (int) (System.currentTimeMillis() / 400 % 4));
        drawStringCenteredShadow(fontRenderer, "Getting you back in" + dots, cx, y + 20, 0xE0E0E0);
        drawStringCenteredShadow(fontRenderer, "Your spot and your things are where you left them.", cx, y + 40, 0xA0A0A0);
        if (tries > 1) drawStringCenteredShadow(fontRenderer, "Try " + tries + (why == null || why.isEmpty() ? "" : " (" + why + ")"), cx, y + 60, 0x808080);
        super.render(mx, my, pt);
    }
}
