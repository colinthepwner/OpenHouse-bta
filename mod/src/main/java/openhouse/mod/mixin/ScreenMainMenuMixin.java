package openhouse.mod.mixin;

import openhouse.mod.OpenHouseMod;
import net.minecraft.client.gui.ScreenMainMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenMainMenu.class, remap = false)
public abstract class ScreenMainMenuMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void openhouse$init(CallbackInfo ci) {
        OpenHouseMod.titleScreen((ScreenMainMenu) (Object) this);
    }
}
