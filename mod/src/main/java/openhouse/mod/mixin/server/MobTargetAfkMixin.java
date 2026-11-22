package openhouse.mod.mixin.server;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.MobPathfinder;
import openhouse.mod.server.AfkServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = MobPathfinder.class, remap = false)
public abstract class MobTargetAfkMixin {
    @Redirect(method = "updateAI", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/entity/MobPathfinder;findPlayerToAttack()Lnet/minecraft/core/entity/Entity;"), require = 0)
    private Entity openhouse$notAfk(MobPathfinder m) {
        Entity e = ((MobPathfinderInvoker) m).openhouse$find();
        try { return AfkServer.ignored(e) ? null : e; } catch (Throwable t) { return e; }
    }
}
