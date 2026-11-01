package openhouse.mod;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnecting;
import net.minecraft.client.gui.ScreenMainMenu;
import net.minecraft.client.gui.TextFieldElement;
import net.minecraft.client.gui.server.ScreenSelectServer;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenPassword extends Screen {
    private final String target, room, host;
    private final int port;
    private final boolean wrong;
    private TextFieldElement real, shown;
    private ButtonElement join, cancel;

    public ScreenPassword(String target, String room, String host, int port, boolean wrong) {
        super(new ScreenSelectServer(new ScreenMainMenu()));
        this.target = target;
        this.room = room;
        this.host = host;
        this.port = port;
        this.wrong = wrong;
    }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        String had = real == null ? "" : real.getText();
        real = new TextFieldElement(this, fontRenderer, width / 2 - 100, height / 4 + 52, 200, 20, had, "");
        real.setMaxStringLength(64);
        real.setFocused(true);
        shown = new TextFieldElement(this, fontRenderer, width / 2 - 100, height / 4 + 52, 200, 20, stars(had.length()), "");
        shown.setMaxStringLength(64);
        shown.setFocused(true);
        join = add(new ButtonElement(1, width / 2 - 100, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.button.join")));
        cancel = add(new ButtonElement(0, width / 2 + 2, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.password.button.cancel")));
        join.enabled = !had.isEmpty();
    }

    private static String stars(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('*');
        return b.toString();
    }

    @Override public void tick() {
        shown.updateCursorCounter();
    }

    private void submit() {
        String pw = real.getText();
        if (pw.isEmpty()) return;
        PasswordStore.put(target, openhouse.RoomPassword.key(pw));
        if (room != null) mc.displayScreen(new ScreenRoomJoin(getParentScreen(), room));
        else mc.displayScreen(new ScreenConnecting(mc, host, port));
    }

    @Override protected void buttonClicked(@NotNull ButtonElement button) {
        if (button == join) submit();
        else if (button == cancel) mc.displayScreen(getParentScreen());
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE) { mc.displayScreen(getParentScreen()); return; }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { submit(); return; }
        real.textboxKeyTyped(c, key);
        shown.setText(stars(real.getText().length()));
        join.enabled = !real.getText().isEmpty();
    }

    @Override public void render(int mx, int my, float partialTick) {
        renderBackground();
        I18n t = I18n.getInstance();
        int cx = width / 2, y = height / 4 - 10;
        drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.password.title"), cx, y, 0xFFFFFF);
        drawStringCenteredShadow(fontRenderer, t.translateKey(wrong ? "gui.openhouse.password.wrong" : "gui.openhouse.password.needed"), cx, y + 22, wrong ? 0xFF6060 : 0xA0A0A0);
        drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.password.remember"), cx, height / 4 + 78, 0x808080);
        shown.drawTextBox();
        super.render(mx, my, partialTick);
    }
}
