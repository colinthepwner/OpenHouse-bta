package openhouse.mod;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenFirewall extends Screen {
    private final Screen back;
    private final boolean block;
    private ButtonElement ok, fix;
    private volatile String said;

    public ScreenFirewall(Screen back, boolean block) { super(back); this.back = back; this.block = block; }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        if (block) {
            fix = add(new ButtonElement(1, width / 2 - 154, height / 4 + 120, 150, 20, t.translateKey("gui.openhouse.firewall.fix")));
            ok = add(new ButtonElement(0, width / 2 + 4, height / 4 + 120, 150, 20, t.translateKey("gui.openhouse.firewall.later")));
        } else ok = add(new ButtonElement(0, width / 2 - 100, height / 4 + 120, t.translateKey("gui.openhouse.firewall.ok")));
    }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (b == ok) {
            if (block && said == null) Firewall.said_later = true;
            mc.displayScreen(back);
        } else if (b == fix && !Firewall.fixing) {
            fix.enabled = false;
            said = "gui.openhouse.firewall.asking";
            Firewall.fix(good -> {
                said = good ? "gui.openhouse.firewall.fixed" : "gui.openhouse.firewall.still";
                fix.enabled = !good;
                if (good) ok.displayString = I18n.getInstance().translateKey("gui.openhouse.firewall.ok");
            });
        }
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN) buttonClicked(ok);
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        I18n t = I18n.getInstance();
        int cx = width / 2, y = height / 4 + 10;
        drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.firewall.title"), cx, y, 0xFFFFFF);
        y += 22;
        String k = block ? "gui.openhouse.firewall.block." : "gui.openhouse.firewall.ask.";
        for (int i = 1; i <= 4; i++) {
            String line = t.translateKey(k + i);
            if (line.equals(k + i)) continue;
            drawStringCenteredShadow(fontRenderer, line, cx, y, i == 1 ? 0xFFFFA0 : 0xC0C0C0);
            y += i == 1 ? 16 : 11;
        }
        String s = said;
        if (s != null) drawStringCenteredShadow(fontRenderer, t.translateKey(s), cx, height / 4 + 100, s.endsWith("fixed") ? 0x80FF80 : s.endsWith("still") ? 0xFF8080 : 0xFFFFFF);
        super.render(mx, my, pt);
    }
}
