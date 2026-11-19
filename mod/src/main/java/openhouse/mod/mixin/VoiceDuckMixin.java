package openhouse.mod.mixin;

import net.minecraft.client.sound.SoundEngine;
import openhouse.mod.voice.Voice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = SoundEngine.class, remap = false)
public abstract class VoiceDuckMixin {
    @ModifyArg(method = { "setMuted", "updateOptions", "playBackgroundSound" },
            at = @At(value = "INVOKE", target = "Lpaulscode/sound/SoundSystem;setVolume(Ljava/lang/String;F)V"), index = 1, require = 0)
    private float openhouse$duck(float v) {
        return v * Voice.duck_now();
    }
}
