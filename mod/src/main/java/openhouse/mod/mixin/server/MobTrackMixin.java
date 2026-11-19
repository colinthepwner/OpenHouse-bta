package openhouse.mod.mixin.server;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.Mob;
import net.minecraft.core.entity.player.Player;
import net.minecraft.server.entity.EntityTrackerEntryImpl;
import net.minecraft.server.entity.EntityTrackerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityTrackerImpl.class, remap = false)
public abstract class MobTrackMixin {
    @Inject(method = "trackEntity(Lnet/minecraft/core/entity/Entity;IIZ)Lnet/minecraft/server/entity/EntityTrackerEntryImpl;", at = @At("RETURN"))
    private void openhouse$everyTick(Entity e, int dist, int delay, boolean motion, CallbackInfoReturnable<EntityTrackerEntryImpl> cir) {
        EntityTrackerEntryImpl t = cir.getReturnValue();
        if (t == null || !(e instanceof Mob) || e instanceof Player) return;
        if (t.movementPacketDelay > 1) t.movementPacketDelay = 1;
        int far = ((EntityTrackerImpl) (Object) this).maxTrackingDistanceThreshold;
        if (t.trackingDistance < far) t.trackingDistance = far;
    }
}
