package openhouse.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.world.Dimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = Minecraft.class, remap = false)
public abstract class StartWorldDimMixin {
    @ModifyArg(method = "startWorld(Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/WorldClient;<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/core/world/save/LevelStorage;Lnet/minecraft/core/world/settings/WorldConfiguration;Lnet/minecraft/core/world/Dimension;)V"), index = 3, require = 0)
    private Dimension openhouse$dimOrOverworld(Dimension d) {
        return d != null ? d : Dimension.OVERWORLD;
    }
}
