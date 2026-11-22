package openhouse.mod.mixin;

import net.minecraft.client.render.entity.MobRenderer;
import net.minecraft.core.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MobRenderer.class, remap = false)
public abstract class MobRendererAfkMixin {
    @Inject(method = "getRenderAlpha", at = @At("RETURN"), cancellable = true, require = 0)
    private void openhouse$afk(Mob m, float pt, CallbackInfoReturnable<Float> cir) {
        try {
            float a = openhouse.mod.Afk.alpha(m);
            if (a < 1) cir.setReturnValue(cir.getReturnValue() * a);
        } catch (Throwable ignored) { }
    }
}
