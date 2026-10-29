package openhouse.mod;

import openhouse.Rtc;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.TextFieldElement;
import net.minecraft.client.gui.paged.Page;
import net.minecraft.client.gui.paged.PageComponent;
import net.minecraft.client.gui.paged.ScreenPaged;
import net.minecraft.client.gui.server.ScreenSelectServer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.item.Items;
import net.minecraft.core.lang.I18n;
import net.minecraft.core.sound.SoundCategory;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class RoomCodePage {
    private static Page page;

    private RoomCodePage() { }

    public static Page page() {
        if (page == null)
            page = ScreenSelectServer.CREATE_WORLD_PAGES.register(new Page("gui.openhouse.page.title", Items.TOOL_COMPASS.getDefaultStack()));
        return page;
    }

    public static void refresh(ScreenSelectServer screen) {
        Page p = page();
        p.clearComponents();
        p.withComponent(new Component(screen));
    }

    static final class Component implements PageComponent {
        final ScreenSelectServer screen;
        final TextFieldElement codeField;
        final ButtonElement joinButton;
        String error = "";

        Component(ScreenSelectServer screen) {
            this.screen = screen;
            I18n t = I18n.getInstance();
            codeField = new TextFieldElement(screen, screen.fontRenderer, 0, 0, 150, 18, OpenHouseMod.typed == null ? "" : OpenHouseMod.typed, "");

            codeField.setMaxStringLength(9);

            codeField.setFocused(true);
            joinButton = new ButtonElement(0, 0, 0, 150, 20, t.translateKey("gui.openhouse.button.join"));
        }

        @Override public void init(@NotNull Minecraft mc) {
            codeField.setFocused(true);
        }

        @Override public int getHeight() {
            return screen.height / 2;
        }

        @Override public void render(@NotNull ScreenPaged s, int x, int y, int width, int relativeMouseX, int relativeMouseY) {
            I18n t = I18n.getInstance();
            int mx = x + relativeMouseX, my = y + relativeMouseY;
            int left = x + width / 2 - codeField.width / 2;
            int top = screen.height / 2 - 40;
            screen.mc.font.render(t.translateKey("gui.openhouse.label.code"), left, top).setShadow().call();
            codeField.xPosition = left;
            codeField.yPosition = screen.mc.font.getLastEndingY() + 1;
            codeField.drawTextBox();
            codeField.updateCursor(screen.mc, mx, my);
            joinButton.xPosition = left;
            joinButton.yPosition = codeField.yPosition + codeField.height + 2;
            joinButton.enabled = !codeField.getText().trim().isEmpty();
            joinButton.drawButton(screen.mc, mx, my);
            int cx = x + width / 2;
            int ty = joinButton.yPosition + joinButton.height + 8;
            if (!error.isEmpty()) screen.drawStringCenteredShadow(screen.fontRenderer, error, cx, ty, 0xFF6060);
            else screen.drawStringCenteredShadow(screen.fontRenderer, t.translateKey("gui.openhouse.label.hint"), cx, ty, 0xA0A0A0);
        }

        @Override public void tick() {
            codeField.updateCursorCounter();
        }

        @Override public boolean onMouseClick(int mouseButton, int x, int y, int width, int relativeMouseX, int relativeMouseY) {
            codeField.mouseClicked(x + relativeMouseX, y + relativeMouseY, 0);
            if (joinButton.enabled && joinButton.isHovered(x + relativeMouseX, y + relativeMouseY)) {
                screen.mc.sndManager.playSound("random.click", SoundCategory.GUI_SOUNDS, 1.0F, 1.0F);
                join();
                return true;
            }
            return false;
        }

        @Override public boolean onMouseMove(int x, int y, int width, int relativeMouseX, int relativeMouseY) { return false; }
        @Override public boolean onMouseRelease(int mouseButton, int x, int y, int width, int relativeMouseX, int relativeMouseY) { return false; }

        @Override public void onKeyPress(int keyCode, char character) {

            if (codeField.isFocused && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                join();
            } else if (codeField.isFocused) {
                codeField.textboxKeyTyped(character, keyCode);
                OpenHouseMod.typed = codeField.getText();
                error = "";
            }
        }

        void join() {
            String code = Rtc.normalizeCode(codeField.getText());
            if (code == null) {
                error = I18n.getInstance().translateKey("gui.openhouse.error.invalid");
                return;
            }
            OpenHouseMod.typed = code;
            codeField.setText(code);

            screen.mc.displayScreen(new ScreenRoomJoin(screen, code));
        }

        @Override public boolean matchesSearchTerm(String term) { return false; }
    }
}
