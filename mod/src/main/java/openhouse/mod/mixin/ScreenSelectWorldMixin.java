package openhouse.mod.mixin;

import java.io.File;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.ScreenSelectWorld;
import openhouse.mod.SaveFileGuard;
import openhouse.mod.OpenHouseMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenSelectWorld.class, remap = false)
public abstract class ScreenSelectWorldMixin {
    @Inject(method = "loadSaves", at = @At("HEAD"), require = 0)
    private void openhouse$checkSaveFiles(CallbackInfo ci) {
        try {
            File saves = new File(FabricLoader.getInstance().getGameDir().toFile(), "saves");
            SaveFileGuard.check(saves, new File(OpenHouseMod.dataDir(), "backups"), s -> OpenHouseMod.LOG.warn(s));
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't check the worlds' save files: {}", t.toString());
        }
    }
}
