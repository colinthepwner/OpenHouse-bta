package openhouse.mod.mixin;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.projectile.Projectile;
import net.minecraft.core.world.World;
import openhouse.mod.MobSmooth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Projectile.class, remap = false)
public abstract class ProjectileSmoothMixin extends Entity {
    @Unique private long openhouse$born;
    @Unique private boolean openhouse$caught;

    protected ProjectileSmoothMixin(World w) { super(w); }

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void openhouse$born(CallbackInfo ci) { openhouse$born = MobSmooth.spawn; }

    @Inject(method = "tick", at = @At("HEAD"))
    private void openhouse$catchUp(CallbackInfo ci) {
        if (openhouse$caught) return;
        openhouse$caught = true;
        if (!MobSmooth.ON || openhouse$born == 0 || world == null || !world.isClientSide) return;
        double adv = Math.max(0, Math.min(2, (MobSmooth.wall.getAsLong() - openhouse$born) / 50.0));
        if (adv > 0) setPos(x + xd * adv, y + yd * adv, z + zd * adv);
    }

    @Override
    public void lerpTo(double sx, double sy, double sz, float yr, float xr, int steps) {
        if (MobSmooth.ON && world != null && world.isClientSide && openhouse$onPath(sx, sy, sz)) return;
        super.lerpTo(sx, sy, sz, yr, xr, steps);
    }

    @Unique
    private boolean openhouse$onPath(double sx, double sy, double sz) {
        double sp = Math.sqrt(xd * xd + yd * yd + zd * zd);
        if (sp < 0.2) return false;
        double dx = x - sx, dy = y - sy, dz = z - sz;
        double along = (dx * xd + dy * yd + dz * zd) / sp;
        double lx = dx - xd / sp * along, ly = dy - yd / sp * along, lz = dz - zd / sp * along;
        return along > -0.5 && along < sp * 6 && lx * lx + ly * ly + lz * lz < 0.75 * 0.75;
    }
}
