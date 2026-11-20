package openhouse.mod.mixin;

import java.util.List;
import net.minecraft.client.gui.options.components.FloatOptionComponent;
import net.minecraft.client.gui.options.components.OptionsCategory;
import net.minecraft.client.gui.options.components.OptionsComponent;
import net.minecraft.client.gui.options.components.ToggleableOptionComponent;
import net.minecraft.client.option.GameSettings;
import openhouse.mod.voice.HurtSoundComponent;
import net.minecraft.client.gui.options.data.OptionsPages;
import openhouse.mod.voice.VoiceVolumeOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OptionsPages.class, remap = false)
public abstract class VoiceOptionsMixin {
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private static void openhouse$hurtRow(CallbackInfo ci) {
        try {
            if (OptionsPages.PLAYER == null) return;
            java.lang.reflect.Field f = ToggleableOptionComponent.class.getDeclaredField("option");
            f.setAccessible(true);
            for (OptionsComponent c : OptionsPages.PLAYER.getComponents()) {
                if (!(c instanceof OptionsCategory)) continue;
                List<OptionsComponent> list = ((OptionsCategoryAccessor) c).openhouse$components();
                for (int i = 0; i < list.size(); i++) {
                    OptionsComponent o = list.get(i);
                    if (o instanceof ToggleableOptionComponent && f.get(o) == GameSettings.PLAYER_HURT_SOUND) { list.set(i, new HurtSoundComponent()); return; }
                }
            }
        } catch (Throwable t) {
            openhouse.mod.OpenHouseMod.LOG.info("[voice] couldn't add Custom to Hurt Sound: {}", t.toString());
        }
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private static void openhouse$locatorRow(CallbackInfo ci) {
        try {
            if (OptionsPages.PLAYER == null) return;
            for (OptionsComponent c : OptionsPages.PLAYER.getComponents()) {
                if (!(c instanceof OptionsCategory)) continue;
                ((OptionsCategoryAccessor) c).openhouse$components().add(new net.minecraft.client.gui.options.components.BooleanOptionComponent(openhouse.mod.LocatorOption.INSTANCE));
                return;
            }
        } catch (Throwable t) {
            openhouse.mod.OpenHouseMod.LOG.info("couldn't add Player Locator to Options: {}", t.toString());
        }
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private static void openhouse$voiceSlider(CallbackInfo ci) {
        try {
            if (OptionsPages.AUDIO == null) return;
            for (OptionsComponent c : OptionsPages.AUDIO.getComponents()) {
                if (!(c instanceof OptionsCategory)) continue;
                List<OptionsComponent> list = ((OptionsCategoryAccessor) c).openhouse$components();
                int last = -1;
                for (int i = 0; i < list.size(); i++) if (list.get(i) instanceof FloatOptionComponent) last = i;
                if (last < 0) continue;
                list.add(last + 1, new FloatOptionComponent(VoiceVolumeOption.INSTANCE));
                return;
            }
        } catch (Throwable t) {
            openhouse.mod.OpenHouseMod.LOG.info("[voice] couldn't add the volume slider to Options: {}", t.toString());
        }
    }
}
