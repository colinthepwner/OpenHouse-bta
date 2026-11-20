package openhouse.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.WorldRenderer;
import openhouse.mod.WorldShots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {
    @Shadow public Minecraft mc;

    @Inject(method = "updateCameraAndRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;renderWorld(FJ)V", shift = At.Shift.AFTER), require = 0)
    private void openhouse$world_shot(float pt, CallbackInfo ci) {
        WorldShots.frame(mc);
    }

    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;renderHand(F)V"), require = 0)
    private void openhouse$locator(float pt, long until, CallbackInfo ci) {
        openhouse.mod.Locator.draw(mc, pt);
    }
}
