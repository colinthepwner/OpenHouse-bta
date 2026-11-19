package openhouse.mod.mixin.server;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.Mob;
import net.minecraft.core.entity.player.Player;
import net.minecraft.server.entity.EntityTrackerEntryImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = EntityTrackerEntryImpl.class, remap = false)
public abstract class MobLookMixin {
    @ModifyConstant(method = "tick(Ljava/util/List;)V", constant = @Constant(intValue = 8))
    private int openhouse$finerTurns(int v) {
        Entity e = ((EntityTrackerEntryImpl) (Object) this).trackedEntity;
        return e instanceof Mob && !(e instanceof Player) ? 2 : v;
    }
}
