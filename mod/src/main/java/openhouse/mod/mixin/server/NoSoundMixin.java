package openhouse.mod.mixin.server;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.sound.SoundCategory;
import net.minecraft.server.world.WorldManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldManager.class, remap = false)
public abstract class NoSoundMixin {
    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$noSound(Entity e, String name, SoundCategory cat, double x, double y, double z, float volume, float pitch, CallbackInfo ci) {
        if (name == null || name.isEmpty()) ci.cancel();
    }
}
