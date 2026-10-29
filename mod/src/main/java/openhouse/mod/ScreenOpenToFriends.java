package openhouse.mod;

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

    @Override public void tick() {
        if (handedOver) return;
        LocalServer.State s = server.state;
        if (!closing && s == LocalServer.State.READY) {
            handedOver = true;
            mc.displayScreen(new ScreenConnecting(mc, "127.0.0.1", server.port));
        } else if (closing && (s == LocalServer.State.STOPPED || s == LocalServer.State.FAILED)) {
            handedOver = true;
            mc.displayScreen(new ScreenMainMenu());
        }

        if (s == LocalServer.State.FAILED && !closing) {
            cancel.visible = cancel.enabled = true;
            cancel.displayString = I18n.getInstance().translateKey("gui.openhouse.button.back");
        }
    }

    @Override protected void buttonClicked(@NotNull ButtonElement button) {
        if (button == cancel) {
            if (server.state != LocalServer.State.FAILED) server.stop();
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
        drawStringCenteredShadow(fontRenderer, t.translateKey(closing ? "gui.openhouse.lan.closing" : "gui.openhouse.lan.opening"), cx, y, 0xFFFFFF);
        boolean failed = server.state == LocalServer.State.FAILED;
        String line = server.detail;
        if (line.isEmpty()) line = closing ? t.translateKey("gui.openhouse.lan.saving") : t.translateKey("gui.openhouse.join.starting");
        drawStringCenteredShadow(fontRenderer, line, cx, y + 30, failed ? 0xFF6060 : 0xA0A0A0);
        super.render(mx, my, partialTick);
    }
}
