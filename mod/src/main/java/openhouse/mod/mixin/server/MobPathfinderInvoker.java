package openhouse.mod.mixin.server;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.MobPathfinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = MobPathfinder.class, remap = false)
public interface MobPathfinderInvoker {
    @Invoker("findPlayerToAttack") Entity openhouse$find();
}
