package openhouse.mod.mixin;

import com.mojang.nbt.tags.CompoundTag;
import net.minecraft.core.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class LostDimMixin {
    @Shadow public int dimension;

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"), require = 0)
    private void openhouse$lostDim(CompoundTag tag, CallbackInfo ci) {
        try {
            if (dimension == 0 || openhouse.mod.LostDim.has(dimension)) return;
            int was = dimension;
            dimension = 0;
            openhouse.mod.LostDim.to_spawn((net.minecraft.core.entity.Entity) (Object) this, was);
        } catch (Throwable t) { openhouse.mod.MissingKeep.oops(t); }
    }
}
