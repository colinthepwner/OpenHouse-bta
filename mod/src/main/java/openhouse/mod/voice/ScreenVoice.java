package openhouse.mod.voice;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.SliderElement;
import net.minecraft.core.lang.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenVoice extends Screen {
    private static final int ON = 1, MUTE = 2, MIC = 3, CLEAN = 4, VOL = 5, KEY = 6, ECHO = 7, MODE = 8, SELF = 9, DONE = 0;
    private ButtonElement on, mute, mic, clean, key, echo, mode, self;
    private SliderElement vol;
    private boolean waiting_key;

    public ScreenVoice(Screen parent) {
        super(parent);
    }

    @Override public void init() {
        buttons.clear();
        int x = width / 2 - 100, y = height / 6;
        on = add(new ButtonElement(ON, x, y, 98, 20, ""));
        mute = add(new ButtonElement(MUTE, x + 102, y, 98, 20, ""));
        mic = add(new ButtonElement(MIC, x, y + 24, 200, 20, ""));
        vol = add(new SliderElement(VOL, x, y + 48, 200, 20, "", VoiceCfg.vol / 2));
        clean = add(new ButtonElement(CLEAN, x, y + 72, 98, 20, ""));
        echo = add(new ButtonElement(ECHO, x + 102, y + 72, 98, 20, ""));
        mode = add(new ButtonElement(MODE, x, y + 96, 98, 20, ""));
        key = add(new ButtonElement(KEY, x + 102, y + 96, 98, 20, ""));
        self = add(new ButtonElement(SELF, x, y + 120, 200, 20, ""));
        add(new ButtonElement(DONE, x, y + 188, 200, 20, I18n.getInstance().translateKey("gui.openhouse.voice.done")));
        labels();
        Voice.testing = true;
    }

    private void labels() {
        I18n t = I18n.getInstance();
        on.displayString = t.translateKey(VoiceCfg.on ? "gui.openhouse.voice.on" : "gui.openhouse.voice.off");
        mute.displayString = t.translateKey(VoiceCfg.muted ? "gui.openhouse.voice.mic.muted" : "gui.openhouse.voice.mic.on");
        mic.displayString = VoiceCfg.mic == null || VoiceCfg.mic.isEmpty()
                ? t.translateKeyAndFormat("gui.openhouse.voice.device.auto", Mics.label(MicAuto.pick, 14))
                : t.translateKeyAndFormat("gui.openhouse.voice.device", Mics.label(VoiceCfg.mic, 22));
        clean.enabled = !Voice.denoise_broken();
        clean.displayString = t.translateKey(Voice.denoise_broken() ? "gui.openhouse.voice.clean.none"
                : VoiceCfg.denoise ? "gui.openhouse.voice.clean.on" : "gui.openhouse.voice.clean.off");
        echo.displayString = t.translateKey(VoiceCfg.echo ? "gui.openhouse.voice.echo.on" : "gui.openhouse.voice.echo.off");
        self.displayString = t.translateKey(Voice.monitor ? "gui.openhouse.voice.self.on" : "gui.openhouse.voice.self.off");
        mode.displayString = t.translateKey(VoiceCfg.ptt ? "gui.openhouse.voice.mode.ptt" : "gui.openhouse.voice.mode.auto");

        int k = VoiceCfg.ptt ? VoiceCfg.ptt_key : VoiceCfg.mute_key;
        key.displayString = waiting_key ? t.translateKey("gui.openhouse.voice.key.press")
                : t.translateKeyAndFormat(VoiceCfg.ptt ? "gui.openhouse.voice.pttkey" : "gui.openhouse.voice.key", k <= 0 ? t.translateKey("gui.openhouse.voice.key.none") : Keyboard.getKeyName(k));
        vol.displayString = t.translateKeyAndFormat("gui.openhouse.voice.volume", Math.round(VoiceCfg.vol * 100));
    }

    @Override protected void buttonClicked(@NotNull ButtonElement b) {
        if (b.id == DONE) { close(); return; }
        if (b.id == ON) VoiceCfg.on = !VoiceCfg.on;
        else if (b.id == MUTE) VoiceCfg.muted = !VoiceCfg.muted;
        else if (b.id == MIC) VoiceCfg.mic = Mics.next(VoiceCfg.mic);
        else if (b.id == CLEAN) VoiceCfg.denoise = !VoiceCfg.denoise;
        else if (b.id == KEY) waiting_key = true;
        else if (b.id == ECHO) VoiceCfg.echo = !VoiceCfg.echo;
        else if (b.id == MODE) VoiceCfg.ptt = !VoiceCfg.ptt;
        else if (b.id == SELF) { Voice.monitor = !Voice.monitor; labels(); return; }
        else return;
        VoiceCfg.save();
        labels();
    }

    @Override public void keyPressed(char c, int k, int mx, int my) {
        if (waiting_key) {
            waiting_key = false;

            int v = k == Keyboard.KEY_BACK || k == Keyboard.KEY_DELETE ? 0 : k;
            if (k != Keyboard.KEY_ESCAPE) { if (VoiceCfg.ptt) VoiceCfg.ptt_key = v; else VoiceCfg.mute_key = v; }
            VoiceCfg.save();
            labels();
            return;
        }
        if (k == Keyboard.KEY_ESCAPE) { close(); return; }

        if (!VoiceCfg.ptt && k == VoiceCfg.mute_key && k > 0) { VoiceCfg.muted = !VoiceCfg.muted; VoiceCfg.save(); labels(); return; }
        super.keyPressed(c, k, mx, my);
    }

    private void close() {
        Voice.testing = false;
        Voice.monitor = false;
        VoiceCfg.save();
        mc.displayScreen(getParentScreen());
    }

    @Override public void removed() {
        Voice.testing = false;
        Voice.monitor = false;
        super.removed();
    }

    @Override public void render(int mx, int my, float pt) {
        renderBackground();
        float v = (float) vol.sliderValue * 2;
        if (Math.abs(v - VoiceCfg.vol) > 0.001F) { VoiceCfg.vol = v; if (!vol.dragging) VoiceCfg.save(); }
        labels();
        I18n t = I18n.getInstance();
        int cx = width / 2, y = height / 6;
        drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.voice.title"), cx, y - 18, 0xFFFFFF);

        int bx = cx - 100, by = y + 148;
        drawRect(bx, by, bx + 200, by + 6, 0xFF202020);
        float lv = (float) Math.min(1, Math.sqrt(Voice.level()));
        int col = Voice.talking() ? VoiceHud.GOLD : 0xFF60C060;
        drawRect(bx + 1, by + 1, bx + 1 + (int) (198 * lv), by + 5, col);
        drawStringCenteredShadow(fontRenderer, status(t), cx, by + 12, 0xA0A0A0);
        super.render(mx, my, pt);
    }

    private String status(I18n t) {
        String p = Voice.problem();
        if (p != null) return p;
        if (!VoiceCfg.on) return t.translateKey("gui.openhouse.voice.status.off");

        if (VoiceCfg.muted) return t.translateKeyAndFormat("gui.openhouse.voice.status.muted", Keyboard.getKeyName(VoiceCfg.mute_key));
        if (VoiceCfg.ptt) return t.translateKeyAndFormat("gui.openhouse.voice.status.ptt", Keyboard.getKeyName(VoiceCfg.ptt_key));

        if (Voice.monitor) return t.translateKey("gui.openhouse.voice.status.self");
        if (!Voice.in_server()) return t.translateKey("gui.openhouse.voice.status.nogame");
        if (!Voice.server_has_voice()) return t.translateKey("gui.openhouse.voice.status.noserver");
        return t.translateKey("gui.openhouse.voice.status.ready");
    }
}
