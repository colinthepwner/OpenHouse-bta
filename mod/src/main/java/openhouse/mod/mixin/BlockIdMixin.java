package openhouse.mod.mixin;

import net.minecraft.core.block.BlockLogicSupplier;
import net.minecraft.core.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Blocks.class)
public abstract class BlockIdMixin {
    @ModifyVariable(method = "register", at = @At("HEAD"), argsOnly = true, require = 0)
    private static int openhouse$id(int id, String key, String ns, int id0, BlockLogicSupplier<?> sup) {
        try { return openhouse.mod.IdFix.block(id, ns); } catch (Throwable t) { return openhouse.mod.IdFix.oops(id, t); }
    }
}
