package openhouse.mod.mixin;

import java.util.UUID;
import net.minecraft.core.util.helper.GetSkinUrlThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GetSkinUrlThread.class, remap = false)
public abstract class GetSkinUrlThreadMixin {
    @Shadow private UUID uuid;

    @Inject(method = "getSkinObject", at = @At("HEAD"))
    private void openhouse$byNameWithoutAccount(String name, CallbackInfoReturnable<String> cir) {
        UUID u = uuid;
        if (u != null && ((u.getMostSignificantBits() == 0 && u.getLeastSignificantBits() == 0) || u.version() == 3)) uuid = null;
    }
}
