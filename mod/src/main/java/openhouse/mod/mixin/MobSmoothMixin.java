package openhouse.mod.mixin;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.Mob;
import openhouse.mod.MobSmooth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Mob.class, remap = false)
public abstract class MobSmoothMixin {
    @Shadow protected double newRotationYaw;
    @Shadow protected double newRotationPitch;
    @Shadow protected int newPosRotationIncrements;
    @Unique private MobSmooth openhouse$sm;
    @Unique private boolean openhouse$moved;
    @Unique private static boolean openhouse$said;

    @Inject(method = "lerpTo", at = @At("HEAD"))
    private void openhouse$sample(double x, double y, double z, float yr, float xr, int steps, CallbackInfo ci) {
        Entity me = (Entity) (Object) this;
        if (!MobSmooth.ON || me.world == null || !me.world.isClientSide) return;
        if (openhouse$sm == null) {
            openhouse$sm = new MobSmooth();
            if (!openhouse$said) { openhouse$said = true; openhouse.mod.OpenHouseMod.LOG.info("smoothing server mobs (MobSmooth; -Dopenhouse.mobsmooth=0 turns it off)"); }
        }
        openhouse$sm.sample(x, y, z);
    }

    @Inject(method = "onLivingUpdate", at = @At("HEAD"))
    private void openhouse$place(CallbackInfo ci) {
        openhouse$moved = false;
        MobSmooth s = openhouse$sm;
        if (s == null) return;
        Entity me = (Entity) (Object) this;

        if (me.vehicle != null || me.world == null || !me.world.isClientSide) { s.reset(); return; }
        if (!s.ready()) return;
        s.tick();

        newPosRotationIncrements = 0;
        double dy = newRotationYaw - me.yRot;
        while (dy < -180) dy += 360;
        while (dy >= 180) dy -= 360;
        me.setRot((float) (me.yRot + dy * 0.5), (float) (me.xRot + (newRotationPitch - me.xRot) * 0.5));
        me.setPos(s.px, s.py, s.pz);
        openhouse$moved = true;
    }

    @Inject(method = "onLivingUpdate", at = @At("TAIL"))
    private void openhouse$keep(CallbackInfo ci) {
        if (!openhouse$moved) return;
        MobSmooth s = openhouse$sm;
        ((Entity) (Object) this).setPos(s.px, s.py, s.pz);
    }
}
