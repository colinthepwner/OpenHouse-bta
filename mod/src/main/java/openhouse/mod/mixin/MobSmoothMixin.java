package openhouse.mod.mixin;

import java.util.List;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.Mob;
import net.minecraft.core.util.helper.MathHelper;
import openhouse.mod.MobSmooth;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
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
    @Unique private int openhouse$dry = 99;
    @Unique private double openhouse$x, openhouse$y, openhouse$z;
    @Unique private static boolean openhouse$said;
    @Unique private static final AABBd openhouse$box = new AABBd(), openhouse$reach = new AABBd();

    @Inject(method = "lerpTo", at = @At("HEAD"))
    private void openhouse$sample(double x, double y, double z, float yr, float xr, int steps, CallbackInfo ci) {
        Entity me = (Entity) (Object) this;
        if (!MobSmooth.ON || me.world == null || !me.world.isClientSide) return;
        if (openhouse$sm == null) {
            openhouse$sm = new MobSmooth();
            if (!openhouse$said) { openhouse$said = true; openhouse.mod.OpenHouseMod.LOG.info("smoothing server mobs (MobSmooth; -Dopenhouse.mobsmooth=0 turns it off)"); }
        }
        boolean has_rot = MobSmooth.take_rot();
        openhouse$sm.sample(x, y, z);
        if (has_rot) openhouse$sm.yaw(yr);
    }

    @Inject(method = "onLivingUpdate", at = @At("HEAD"))
    private void openhouse$place(CallbackInfo ci) {
        openhouse$moved = false;
        MobSmooth s = openhouse$sm;
        if (s == null) return;
        Entity me = (Entity) (Object) this;

        if (me.vehicle != null || me.locallySimulated || me.world == null || !me.world.isClientSide) { s.reset(); return; }
        if (!s.ready()) return;

        openhouse$dry = me.isInWater() ? 0 : Math.min(99, openhouse$dry + 1);
        s.wet(openhouse$dry < 10);
        s.hurt(((Mob) (Object) this).hurtTime > 0);
        s.tick();

        newPosRotationIncrements = 0;
        double yaw = s.has_yaw() ? s.head() : newRotationYaw;
        double dy = yaw - me.yRot;
        while (dy < -180) dy += 360;
        while (dy >= 180) dy -= 360;
        me.setRot((float) (me.yRot + dy), (float) (me.xRot + (newRotationPitch - me.xRot) * 0.7));
        openhouse$clip(me, s);
        me.setPos(openhouse$x, openhouse$y, openhouse$z);
        openhouse$moved = true;
    }

    @Inject(method = "onLivingUpdate", at = @At("TAIL"))
    private void openhouse$keep(CallbackInfo ci) {
        if (!openhouse$moved) return;
        ((Entity) (Object) this).setPos(openhouse$x, openhouse$y, openhouse$z);
    }

    @Inject(method = "tryToDespawn", at = @At("HEAD"))
    private void openhouse$frozen(CallbackInfo ci) {
        Mob me = (Mob) (Object) this;
        if (openhouse$sm == null || me.world == null || !me.world.isClientSide) return;
        me.xo = me.x; me.yo = me.y; me.zo = me.z; me.yRotO = me.yRot; me.xRotO = me.xRot;
        me.yBodyRotO = me.yBodyRot; me.walkAnimSpeedO = me.walkAnimSpeed;
        openhouse$place(ci);
    }

    @Unique
    private void openhouse$clip(Entity me, MobSmooth s) {
        openhouse$x = s.px; openhouse$y = s.py; openhouse$z = s.pz;
        double dx = s.px - s.lx, dy = s.py - s.ly, dz = s.pz - s.lz, d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 1e-8 || d2 > 64) return;
        double step = me.footSize;
        AABBd box = openhouse$box.set(me.bb).translate(s.lx - me.x, s.ly - me.y, s.lz - me.z);

        AABBd reach = MathHelper.aabbExpand(box, dx, dy, dz, openhouse$reach);
        reach.maxY += step;
        List<AABBdc> cubes = me.world.getCubes(me, reach);
        if (cubes.isEmpty()) return;
        double ox = box.minX, oy = box.minY, oz = box.minZ;
        double cy = dy, cx = dx, cz = dz;
        for (int i = 0; i < cubes.size(); i++) cy = MathHelper.aabbClipYCollide(cubes.get(i), box, cy);
        box.translate(0, cy, 0);
        for (int i = 0; i < cubes.size(); i++) cx = MathHelper.aabbClipXCollide(cubes.get(i), box, cx);
        box.translate(cx, 0, 0);
        for (int i = 0; i < cubes.size(); i++) cz = MathHelper.aabbClipZCollide(cubes.get(i), box, cz);
        box.translate(0, 0, cz);
        if ((cx != dx || cz != dz) && step > 0) {

            AABBd up = openhouse$reach.set(box).translate(ox - box.minX, oy - box.minY, oz - box.minZ);
            double sy = step, sx = dx, sz = dz;
            for (int i = 0; i < cubes.size(); i++) sy = MathHelper.aabbClipYCollide(cubes.get(i), up, sy);
            up.translate(0, sy, 0);
            for (int i = 0; i < cubes.size(); i++) sx = MathHelper.aabbClipXCollide(cubes.get(i), up, sx);
            up.translate(sx, 0, 0);
            for (int i = 0; i < cubes.size(); i++) sz = MathHelper.aabbClipZCollide(cubes.get(i), up, sz);
            up.translate(0, 0, sz);
            double down = dy - sy;
            for (int i = 0; i < cubes.size(); i++) down = MathHelper.aabbClipYCollide(cubes.get(i), up, down);
            up.translate(0, down, 0);
            if (sx * sx + sz * sz > cx * cx + cz * cz + 1e-6) box.set(up);
        }
        openhouse$x = s.lx + (box.minX - ox); openhouse$y = s.ly + (box.minY - oy); openhouse$z = s.lz + (box.minZ - oz);
    }
}
