package openhouse.mod.mixin;

import net.minecraft.client.Minecraft;
import openhouse.mod.voice.Voice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
public abstract class VoiceClientMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void openhouse$voiceTick(CallbackInfo ci) {
        try { Voice.frame((Minecraft) (Object) this); } catch (Throwable ignored) { }
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void openhouse$voiceOff(CallbackInfo ci) {
        Voice.shutdown();
    }
}
