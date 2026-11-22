package openhouse.mod.mixin.server;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.Mob;
import net.minecraft.core.entity.player.Player;
import net.minecraft.server.entity.EntityTrackerEntryImpl;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = EntityTrackerEntryImpl.class, remap = false)
public abstract class MobLookMixin {
    @ModifyExpressionValue(method = "tick(Ljava/util/List;)V", at = @At(value = "CONSTANT", args = "intValue=8"), require = 0)
    private int openhouse$finerTurns(int v) {
        Entity e = ((EntityTrackerEntryImpl) (Object) this).trackedEntity;
        return e instanceof Mob && !(e instanceof Player) ? 2 : v;
    }
}
