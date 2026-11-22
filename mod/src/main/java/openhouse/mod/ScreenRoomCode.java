package openhouse.mod;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.TextFieldElement;
import net.minecraft.core.lang.I18n;
import openhouse.Rtc;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

import java.util.List;

public final class ScreenRoomCode extends Screen {
    private TextFieldElement field;
    private ButtonElement join, back;
    private final ButtonElement[] near = new ButtonElement[4];
    private List<LanRooms.Room> shown = java.util.Collections.emptyList();
    private String error = "";

    public ScreenRoomCode(Screen parent) { super(parent); }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        String had = field == null ? (OpenHouseMod.typed == null ? "" : OpenHouseMod.typed) : field.getText();
        field = new TextFieldElement(this, fontRenderer, width / 2 - 100, height / 4 + 4, 200, 20, had, "");
        field.setMaxStringLength(Rtc.PICKED_MAX + 4);
        field.setFocused(true);
        join = add(new ButtonElement(1, width / 2 - 100, height / 4 + 30, 98, 20, t.translateKey("gui.openhouse.button.join")));
        back = add(new ButtonElement(0, width / 2 + 2, height / 4 + 30, 98, 20, t.translateKey("gui.openhouse.button.back")));
        for (int i = 0; i < near.length; i++) {
            near[i] = add(new ButtonElement(10 + i, width / 2 - 100, height / 4 + 92 + i * 22, 200, 20, ""));
            near[i].visible = near[i].enabled = false;
        }
        LanRooms.start();
    }

    @Override public void tick() {
        field.updateCursorCounter();
        join.enabled = !field.getText().trim().isEmpty();
        I18n t = I18n.getInstance();
        shown = LanRooms.now();
        for (int i = 0; i < near.length; i++) {
            boolean on = i < shown.size();
            near[i].visible = near[i].enabled = on;
            if (on) { LanRooms.Room r = shown.get(i); near[i].displayString = t.translateKeyAndFormat(r.pw ? "gui.openhouse.nearby.entry.password" : "gui.openhouse.nearby.entry", r.world, r.code); }
        }
    }

    private void go(String typed) {
        String code = Rtc.normalizeCode(typed);
        if (code == null) { error = I18n.getInstance().translateKey("gui.openhouse.error.invalid"); return; }
        OpenHouseMod.typed = code;
        LanRooms.stop();
        mc.displayScreen(new ScreenRoomJoin(this, code));
    }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (b == join) go(field.getText());
        else if (b == back) { LanRooms.stop(); mc.displayScreen(getParentScreen()); }
        else for (int i = 0; i < near.length; i++) if (b == near[i] && i < shown.size()) go(shown.get(i).code);
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE) { buttonClicked(back); return; }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { go(field.getText()); return; }
        field.textboxKeyTyped(c, key);
        OpenHouseMod.typed = field.getText();
        error = "";
    }

    @Override public void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        field.mouseClicked(mx, my, button);
        field.setFocused(true);
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        I18n t = I18n.getInstance();
        int cx = width / 2;
        drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.page.title"), cx, height / 4 - 30, 0xFFFFFF);
        drawStringShadow(fontRenderer, t.translateKey("gui.openhouse.label.code"), cx - 100, height / 4 - 8, 0xA0A0A0);
        field.drawTextBox();
        drawStringCenteredShadow(fontRenderer, error.isEmpty() ? t.translateKey("gui.openhouse.label.hint") : error, cx, height / 4 + 58, error.isEmpty() ? 0xA0A0A0 : 0xFF6060);
        drawStringCenteredShadow(fontRenderer, t.translateKey(shown.isEmpty() ? "gui.openhouse.nearby.none" : "gui.openhouse.nearby.title"), cx, height / 4 + 78, 0xFFFFFF);
        super.render(mx, my, pt);
    }
}
