package openhouse.mod.mixin;

import net.minecraft.core.item.Item;
import net.minecraft.core.util.collection.NamespaceID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Item.class)
public abstract class ItemIdMixin {
    @ModifyVariable(method = "<init>(Lnet/minecraft/core/util/collection/NamespaceID;Ljava/lang/String;I)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private static int openhouse$id(int id, NamespaceID ns, String key, int id0) {
        try { return openhouse.mod.IdFix.item(id, ns == null ? null : ns.toString()); } catch (Throwable t) { return openhouse.mod.IdFix.oops(id, t); }
    }
}
