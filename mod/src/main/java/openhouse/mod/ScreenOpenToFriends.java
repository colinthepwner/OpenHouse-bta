package openhouse.mod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;
import net.minecraft.client.gui.ScreenMainMenu;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenOpenToFriends extends Screen {
    private final LocalServer server;
    private final boolean closing;
    private boolean handedOver;
    private ButtonElement cancel;

    public ScreenOpenToFriends(LocalServer server, boolean closing) {
        this.server = server;
        this.closing = closing;
    }

    @Override public void init() {
        buttons.clear();
        cancel = add(new ButtonElement(0, width / 2 - 100, height / 4 + 132, I18n.getInstance().translateKey(closing ? "gui.openhouse.button.back" : "gui.openhouse.lan.button.cancel")));
        cancel.visible = cancel.enabled = !closing;
    }

    private boolean crashed() {
        return !closing && server.crash != null;
    }

    @Override public void tick() {
        if (handedOver) return;
        LocalServer.State s = server.state;
        if (!closing && s == LocalServer.State.READY && !crashed()) {
            handedOver = true;
            mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", server.port));
        } else if (closing && (s == LocalServer.State.STOPPED || s == LocalServer.State.FAILED)) {
            handedOver = true;
            mc.displayScreen(new ScreenMainMenu());
        }

        if ((s == LocalServer.State.FAILED || crashed()) && !closing) {
            cancel.visible = cancel.enabled = true;
            cancel.displayString = I18n.getInstance().translateKey("gui.openhouse.button.back");
        }
    }

    @Override protected void buttonClicked(@NotNull ButtonElement button) {
        if (button == cancel) {

            if (server.state != LocalServer.State.FAILED && server.crash == null) server.stop();
            mc.displayScreen(new ScreenMainMenu());
        }
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE && cancel.enabled) buttonClicked(cancel);
    }

    @Override public void render(int mx, int my, float partialTick) {
        renderBackground();
        I18n t = I18n.getInstance();
        int cx = width / 2, y = height / 2 - 50;
        if (crashed()) {
            drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.lan.crashed"), cx, y, 0xFFFFFF);
            int ly = y + 24;
            String mod = server.crashMod;
            if (mod != null) {
                drawStringCenteredShadow(fontRenderer, t.translateKeyAndFormat("gui.openhouse.lan.crashed.mod", mod), cx, ly, 0xFFFFFF);
                ly += 14;
            }
            for (String l : wrap(server.crash, 52, 4)) {
                drawStringCenteredShadow(fontRenderer, l, cx, ly, 0xFF6060);
                ly += 11;
            }
            drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.lan.crashed.details"), cx, ly + 6, 0xA0A0A0);
            super.render(mx, my, partialTick);
            return;
        }
        drawStringCenteredShadow(fontRenderer, t.translateKey(closing ? "gui.openhouse.lan.closing" : "gui.openhouse.lan.opening"), cx, y, 0xFFFFFF);
        boolean failed = server.state == LocalServer.State.FAILED;
        String line = server.detail;
        if (line.isEmpty()) line = closing ? t.translateKey("gui.openhouse.lan.saving") : t.translateKey("gui.openhouse.join.starting");
        drawStringCenteredShadow(fontRenderer, line, cx, y + 30, failed ? 0xFF6060 : 0xA0A0A0);
        super.render(mx, my, partialTick);
    }

    static List<String> wrap(String s, int width, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            while (w.length() > width) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                out.add(w.substring(0, width));
                w = w.substring(width);
            }
            if (cur.length() > 0 && cur.length() + 1 + w.length() > width) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.size() > max) {
            out = new ArrayList<>(out.subList(0, max));
            String last = out.get(max - 1);
            out.set(max - 1, (last.length() > width - 3 ? last.substring(0, width - 3) : last) + "...");
        }
        return out;
    }
}
