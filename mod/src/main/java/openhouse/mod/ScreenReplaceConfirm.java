package openhouse.mod;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenReplaceConfirm extends Screen {
    private ButtonElement yes, no;
    private final Runnable go;

    public ScreenReplaceConfirm(Screen back, Runnable go) { super(back); this.go = go; }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        yes = add(new ButtonElement(1, width / 2 - 154, height / 4 + 120, 150, 20, t.translateKey("gui.openhouse.rewind.pause.replace")));
        no = add(new ButtonElement(0, width / 2 + 4, height / 4 + 120, 150, 20, t.translateKey("gui.openhouse.rewind.button.cancel")));
    }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (b == no) mc.displayScreen(getParentScreen());
        else if (b == yes) go.run();
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE) buttonClicked(no);
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        I18n t = I18n.getInstance();
        String name = WorldGuard.visit_name == null ? "" : WorldGuard.visit_name;
        int cx = width / 2, y = height / 4 + 10;
        drawStringCenteredShadow(fontRenderer, t.translateKeyAndFormat("gui.openhouse.rewind.replace.title", name), cx, y, 0xFFFFFF);
        y += 22;
        for (String k : new String[] { "gui.openhouse.rewind.replace.l1", "gui.openhouse.rewind.replace.l2", "gui.openhouse.rewind.replace.l3", "gui.openhouse.rewind.replace.l4" }) {
            drawStringCenteredShadow(fontRenderer, t.translateKeyAndFormat(k, name), cx, y, k.endsWith("l1") || k.endsWith("l2") ? 0xFFD0D0 : 0xA0A0A0);
            y += k.endsWith("l2") ? 18 : 11;
        }
        super.render(mx, my, pt);
        Icons.red(this, fontRenderer, yes);
    }
}
