package openhouse.mod.voice;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.options.ScreenOptions;
import net.minecraft.client.gui.options.components.ToggleableOptionComponent;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.option.OptionToggleable;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;

public class HurtSoundComponent extends ToggleableOptionComponent<String> {
    static final int ORANGE = 0xFFFF9A2E, SET = 0xFFE0E0E0, TICK = 0xFF5BE35B;
    static final long CLICK_MS = 350;

    static final class Choice extends OptionToggleable<String> {
        Choice(String[] vals) { super("player.hurtsound", vals[0], vals); }
        @Override public void fromOptionsString(@NotNull String s) { }
        @Override public boolean isSlider() { return true; }
        @NotNull @Override public String getDisplayString() { return I18n.getInstance().translateKey("options.player.hurtsound." + value); }

        @NotNull @Override public String getDisplayStringValue() { return getDisplayString(); }
    }

    private static String[] values() {

        if (!has_choice()) return new String[] { "default", "custom" };
        Player.PlayerConfig.HurtSound[] hs = Player.PlayerConfig.HurtSound.values();
        String[] v = new String[hs.length + 1];
        for (int i = 0; i < hs.length; i++) v[i] = hs[i].key;
        v[hs.length] = "custom";
        return v;
    }

    static boolean has_choice() {
        try { return GameSettings.PLAYER_HURT_SOUND != null; } catch (Throwable t) { return false; }
    }

    private final ButtonElement micb = new ButtonElement(0, 0, 0, 20, 20, "");
    private boolean pressing, over_mic;
    private long down_at, said_until;
    private String said_shown;

    public HurtSoundComponent() {
        super(new Choice(values()));
        option.addOnChangeCallback((mc, o) -> apply());
        sync();
    }

    private static int count() { return has_choice() ? GameSettings.PLAYER_HURT_SOUND.size() : 1; }
    private static int index() { return has_choice() ? GameSettings.PLAYER_HURT_SOUND.getValueIndex() : 0; }

    private void sync() { option.setValueWithIndex(VoiceCfg.hurt_custom ? count() : index()); }

    private void apply() {
        int i = option.getValueIndex(), n = count();
        boolean custom = i >= n;
        if (custom != VoiceCfg.hurt_custom) { VoiceCfg.hurt_custom = custom; VoiceCfg.save(); }
        if (custom) HurtClip.neutral_under();
        else if (has_choice() && GameSettings.PLAYER_HURT_SOUND.getValueIndex() != i) {
            GameSettings.PLAYER_HURT_SOUND.setValueWithIndex(i);
            GameSettings.PLAYER_HURT_SOUND.onUpdate();
        }
    }

    @Override
    public void init(Minecraft mc) { sync(); super.init(mc); }

    private String label() {
        I18n t = I18n.getInstance();
        if (HurtClip.recording()) return t.translateKey("gui.openhouse.hurt.rec");
        if (HurtClip.loading) return t.translateKey("gui.openhouse.hurt.checking");
        String s = HurtClip.said;
        if (s != null) { HurtClip.said = null; said_shown = s; said_until = System.currentTimeMillis() + 3000; }
        if (said_shown != null && System.currentTimeMillis() < said_until) return t.translateKey(said_shown);
        said_shown = null;
        return null;
    }

    @Override
    public void resetValue() {
        VoiceCfg.hurt_custom = false; VoiceCfg.save();
        if (has_choice()) { GameSettings.PLAYER_HURT_SOUND.set(GameSettings.PLAYER_HURT_SOUND.getDefaultValue()); GameSettings.PLAYER_HURT_SOUND.onUpdate(); }
        sync(); onChanged();
    }

    @Override
    public boolean isDefault() { return !VoiceCfg.hurt_custom && (!has_choice() || GameSettings.PLAYER_HURT_SOUND.value == GameSettings.PLAYER_HURT_SOUND.getDefaultValue()); }

    @Override
    public String getTooltipTranslationKey() { return over_mic ? "gui.openhouse.hurt.tip" : null; }

    @Override
    public void render(@NotNull ScreenOptions so, int x, int y, int width, int rmx, int rmy) {

        int want = VoiceCfg.hurt_custom ? count() : index();
        if (!slider.dragging && option.getValueIndex() != want) { option.setValueWithIndex(want); slider.sliderValue = want; onChanged(); }
        String name = I18n.getInstance().translateKey("options.player.hurtsound");
        boolean row = rmx >= 0 && rmx <= width && rmy >= 2 && rmy <= getHeight() - 2;
        over_mic = rmx >= width - 122 && rmx < width - 102 && rmy >= 2 && rmy < 22;
        mc.font.render((CharSequence) name, x, y + getHeight() / 2 - 4).setShadow().setColor(row ? -96 : -1).call();
        int lw = mc.font.stringWidth(name), line_to = x + width - 100 - 8 - 22;
        if (x + lw + 8 < line_to) drawRect(x + lw + 8, y + getHeight() / 2, line_to, y + getHeight() / 2 + 1, 1602191231);
        String l = label();
        if (l != null) slider.displayString = l; else if (!slider.dragging) onChanged();
        resetButton.enabled = false;
        renderButton(x, y, width - 100, 2, 100, 20, rmx, rmy);
        int mx = x + rmx, my = y + rmy;
        micb.xPosition = x + width - 122; micb.yPosition = y + 2;
        micb.drawButton(mc, mx, my);
        boolean has = HurtClip.has();
        int c = HurtClip.recording() ? VoiceHud.pulse(VoiceHud.RED) : has ? SET : ORANGE;
        int ix = micb.xPosition + (20 - 9) / 2, iy = micb.yPosition + (20 - 12) / 2;
        VoiceHud.mic(micb, ix + 1, iy + 1, VoiceHud.SHADOW, false);
        VoiceHud.mic(micb, ix, iy, c, false);
        if (has && !HurtClip.recording()) {
            int tx = micb.xPosition + 13, ty = micb.yPosition + 12;
            tick(tx + 1, ty + 1, VoiceHud.SHADOW);
            tick(tx, ty, TICK);
        }
    }

    private void tick(int x, int y, int c) {
        micb.drawRect(x, y + 1, x + 1, y + 3, c);
        micb.drawRect(x + 1, y + 2, x + 2, y + 4, c);
        micb.drawRect(x + 2, y + 1, x + 3, y + 3, c);
        micb.drawRect(x + 3, y, x + 4, y + 2, c);
        micb.drawRect(x + 4, y - 1, x + 5, y + 1, c);
    }

    @Override
    public void onMouseClick(int mb, int x, int y, int width, int rmx, int rmy) {
        boolean on_mic = rmx >= width - 122 && rmx < width - 102 && rmy >= 2 && rmy < 22;
        if (on_mic) {
            if (HurtClip.loading || System.currentTimeMillis() - HurtClip.picker_closed < HurtClip.PICKER_GRACE) return;
            mc.sndManager.playSound("random.click", net.minecraft.core.sound.SoundCategory.GUI_SOUNDS, 1.0F, 1.0F);
            if (mb == 1) { said_shown = null; HurtClip.upload(); return; }
            if (mb != 0 || HurtClip.loading) return;
            if (HurtClip.recording()) { HurtClip.stop(); pressing = false; return; }
            HurtClip.said = null; said_shown = null;
            HurtClip.start();
            pressing = true;
            down_at = System.currentTimeMillis();
            return;
        }
        if (HurtClip.busy()) return;
        super.onMouseClick(mb, x, y, width, rmx, rmy);
    }

    @Override
    public void onMouseRelease(int mb, int x, int y, int width, int rmx, int rmy) {
        if (pressing) {
            pressing = false;
            if (System.currentTimeMillis() - down_at < CLICK_MS) HurtClip.until_quiet(); else HurtClip.stop();
            return;
        }
        super.onMouseRelease(mb, x, y, width, rmx, rmy);
    }

    @Override
    public void onClose() { if (HurtClip.recording()) HurtClip.stop(); pressing = false; }
}
