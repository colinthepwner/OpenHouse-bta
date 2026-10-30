package openhouse.mod.mixin;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import openhouse.mod.OpenHousePreLaunch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = { "turniplabs.halplibe.util.ConfigHandler", "turniplabs.halplibe.util.TomlConfigHandler" }, remap = false)
public abstract class HalpLibeConfigMixin {

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/fabricmc/loader/api/FabricLoader;getGameDir()Ljava/nio/file/Path;"), require = 0)
    private static Path openhouse$roomConfigRoot(FabricLoader loader) {
        Path pack = OpenHousePreLaunch.configRoot();
        return pack != null ? pack : loader.getGameDir();
    }
}
