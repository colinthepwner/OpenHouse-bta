package openhouse.mod;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;

public final class ScreenVisitEnd extends Screen {
    private final LocalServer server;
    private ButtonElement back, replace;

    public ScreenVisitEnd(LocalServer server) { this.server = server; }

    private boolean stopped() {
        LocalServer.State s = server == null ? null : server.state;
        return s == null || s == LocalServer.State.STOPPED || s == LocalServer.State.FAILED;
    }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        back = add(new ButtonElement(1, width / 2 - 100, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.rewind.pause.back.short")));
        replace = add(new ButtonElement(2, width / 2 + 2, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.rewind.pause.replace.short")));
        tick();
    }

    @Override public void tick() { back.enabled = replace.enabled = stopped(); }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (!b.enabled || !stopped()) return;
        if (b == replace) mc.displayScreen(new ScreenReplaceConfirm(this, () -> mc.displayScreen(ScreenRewind.leaveVisit(true))));
        else mc.displayScreen(ScreenRewind.leaveVisit(false));
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        I18n t = I18n.getInstance();
        drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.rewind.end.title"), width / 2, height / 4 + 20, 0xFFFFFF);
        drawStringCenteredShadow(fontRenderer, t.translateKey(stopped() ? "gui.openhouse.rewind.end.ask" : "gui.openhouse.lan.saving"), width / 2, height / 4 + 40, 0xA0A0A0);
        super.render(mx, my, pt);
        Icons.red(this, fontRenderer, replace);
    }
}
