package openhouse.mod.mixin.server;

import net.minecraft.server.MinecraftServer;
import openhouse.mod.server.VoiceRelay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftServer.class, remap = false)
public abstract class VoiceTickMixin {
    @Inject(method = "doTick", at = @At("TAIL"), require = 0)
    private void openhouse$voiceTick(CallbackInfo ci) {
        try { VoiceRelay.tick(); } catch (Throwable ignored) { }
    }
}
