package openhouse.mod.mixin;

import net.minecraft.core.world.save.LevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelData.class)
public abstract class LevelDimMixin {
    @Inject(method = "getDimension()I", at = @At("RETURN"), cancellable = true, require = 0)
    private void openhouse$dimOrOverworld(CallbackInfoReturnable<Integer> cir) {
        try {
            int d = cir.getReturnValueI();
            if (d != 0 && !openhouse.mod.LostDim.has(d)) cir.setReturnValue(0);
        } catch (Throwable t) { openhouse.mod.MissingKeep.oops(t); }
    }
}
