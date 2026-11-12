package openhouse.mod.mixin;

import java.io.File;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.ScreenSelectWorld;
import openhouse.mod.LevelDatGuard;
import openhouse.mod.OpenHouseMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenSelectWorld.class, remap = false)
public abstract class ScreenSelectWorldMixin {
    @Inject(method = "loadSaves", at = @At("HEAD"), require = 0)
    private void openhouse$checkLevelDats(CallbackInfo ci) {
        try {
            File saves = new File(FabricLoader.getInstance().getGameDir().toFile(), "saves");
            LevelDatGuard.check(saves, new File(OpenHouseMod.dataDir(), "backups"), s -> OpenHouseMod.LOG.warn(s));
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't check the worlds' level.dat files: {}", t.toString());
        }
    }
}
