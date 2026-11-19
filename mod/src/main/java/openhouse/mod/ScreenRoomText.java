package openhouse.mod;

import java.util.function.Function;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.TextFieldElement;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenRoomText extends Screen {
    private final String title, hint;
    private final boolean masked;
    private final int max;
    private final Function<String, String> check;
    private TextFieldElement real, shown;
    private ButtonElement done, cancel;
    private String bad;

    public ScreenRoomText(Screen parent, String title, String hint, boolean masked, int max, Function<String, String> check) {
        super(parent);
        this.title = title; this.hint = hint; this.masked = masked; this.max = max; this.check = check;
    }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        String had = real == null ? "" : real.getText();
        real = new TextFieldElement(this, fontRenderer, width / 2 - 100, height / 4 + 52, 200, 20, had, "");
        real.setMaxStringLength(max);
        real.setFocused(true);
        shown = new TextFieldElement(this, fontRenderer, width / 2 - 100, height / 4 + 52, 200, 20, masked ? stars(had.length()) : had, "");
        shown.setMaxStringLength(max);
        shown.setFocused(true);
        done = add(new ButtonElement(1, width / 2 - 100, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.roomtext.done")));
        cancel = add(new ButtonElement(0, width / 2 + 2, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.rewind.button.cancel")));
    }

    private static String stars(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('*');
        return b.toString();
    }

    @Override public void tick() { shown.updateCursorCounter(); }

    private void submit() {
        bad = check.apply(real.getText().trim());
        if (bad == null) mc.displayScreen(getParentScreen());
    }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (b == done) submit();
        else if (b == cancel) mc.displayScreen(getParentScreen());
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE) { mc.displayScreen(getParentScreen()); return; }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { submit(); return; }
        real.textboxKeyTyped(c, key);
        shown.setText(masked ? stars(real.getText().length()) : real.getText());
        same_cursor(real, shown);
        bad = null;
    }

    private static java.lang.reflect.Field editor;
    public static void same_cursor(TextFieldElement from, TextFieldElement to) {
        try {
            if (editor == null) { editor = TextFieldElement.class.getDeclaredField("editor"); editor.setAccessible(true); }
            net.minecraft.client.gui.text.TextFieldEditor a = (net.minecraft.client.gui.text.TextFieldEditor) editor.get(from), b = (net.minecraft.client.gui.text.TextFieldEditor) editor.get(to);
            b.setCursor(a.getCursor());
        } catch (Throwable e) {
            try { ((net.minecraft.client.gui.text.TextFieldEditor) editor.get(to)).setCursor(10000); } catch (Throwable ignored) { }
        }
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        int cx = width / 2, y = height / 4 - 10;
        drawStringCenteredShadow(fontRenderer, title, cx, y, 0xFFFFFF);
        drawStringCenteredShadow(fontRenderer, hint, cx, y + 22, 0xA0A0A0);
        if (bad != null) drawStringCenteredShadow(fontRenderer, bad, cx, height / 4 + 78, 0xFF6060);
        shown.drawTextBox();
        super.render(mx, my, pt);
    }

    public static void askPassword(Screen pause) {
        I18n t = I18n.getInstance();
        pause.mc.displayScreen(new ScreenRoomText(pause, t.translateKey("gui.openhouse.roomtext.pw.title"), t.translateKey("gui.openhouse.roomtext.pw.hint"), true, 64, pw -> {
            if (!LocalServer.hosting() || pause.mc.thePlayer == null) return t.translateKey("gui.openhouse.roomtext.closed");
            pause.mc.thePlayer.sendChatMessage(pw.isEmpty() ? "/oh password off" : "/oh password " + pw);
            return null;
        }));
    }

    public static void askCode(Screen pause) {
        I18n t = I18n.getInstance();
        pause.mc.displayScreen(new ScreenRoomText(pause, t.translateKey("gui.openhouse.roomtext.code.title"), t.translateKey("gui.openhouse.roomtext.code.hint"), false, 16, typed -> {
            if (!LocalServer.hosting() || pause.mc.thePlayer == null) return t.translateKey("gui.openhouse.roomtext.closed");
            if (typed.isEmpty()) { pause.mc.thePlayer.sendChatMessage("/oh new"); return null; }
            String c = openhouse.Rtc.normalizeCode(typed);
            if (c == null || !openhouse.Rtc.isPicked(c)) return t.translateKey("gui.openhouse.roomtext.code.bad");
            pause.mc.thePlayer.sendChatMessage("/oh set " + c);
            return null;
        }));
    }
}
