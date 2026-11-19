package openhouse.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.hud.HudIngame;
import openhouse.mod.voice.Voice;
import openhouse.mod.voice.VoiceHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HudIngame.class, remap = false)
public abstract class VoiceHudMixin {
    @Shadow protected Minecraft mc;

    @Inject(method = "renderGameOverlay", at = @At("TAIL"))
    private void openhouse$voiceHud(float pt, boolean flag, int mx, int my, CallbackInfo ci) {
        try {
            Voice.frame(mc);
            VoiceHud.draw((Gui) (Object) this, mc, mc.resolution.getScaledWidthScreenCoords(), mc.resolution.getScaledHeightScreenCoords());
        } catch (Throwable ignored) { }
    }
}
