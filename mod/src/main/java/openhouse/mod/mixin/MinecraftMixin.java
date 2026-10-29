package openhouse.mod.mixin;

import openhouse.mod.OpenHouseMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftMixin {
    @Inject(method = "startWorld", at = @At("HEAD"))
    private void openhouse$startWorld(String worldDirName, CallbackInfo ci) {
        OpenHouseMod.singlePlayerWorld = worldDirName;
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void openhouse$tick(CallbackInfo ci) {
        OpenHouseMod.clientTick((Minecraft) (Object) this);
    }
}
