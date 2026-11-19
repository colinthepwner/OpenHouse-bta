package openhouse.mod.voice;

import net.minecraft.client.option.OptionFloat;

public final class VoiceVolumeOption extends OptionFloat {
    public static final VoiceVolumeOption INSTANCE = new VoiceVolumeOption();

    private VoiceVolumeOption() {
        super("openhouse.voice", VoiceCfg.vol / 2);
        withDisplayStringProvider((mc, t, o) -> o.value <= 0 ? t.translateKey("options.off") : Math.round(o.value * 200) + "%");
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        VoiceCfg.vol = Math.max(0, Math.min(2, value * 2));
        VoiceCfg.save();
    }

    static void sync() { INSTANCE.value = VoiceCfg.vol / 2; }
}
