package openhouse.mod.mixin;

import com.mojang.nbt.tags.CompoundTag;
import com.mojang.nbt.tags.ListTag;
import java.util.List;
import net.minecraft.core.player.inventory.container.ContainerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ContainerInventory.class)
public abstract class KeepInventoryMixin {
    @Unique private List<CompoundTag> openhouse$gone;

    @Inject(method = { "load(Lcom/mojang/nbt/tags/ListTag;)V", "readFromNBT(Lcom/mojang/nbt/tags/ListTag;)V" }, at = @At("HEAD"), require = 0)
    private void openhouse$read(ListTag l, CallbackInfo ci) {
        try { openhouse$gone = openhouse.mod.MissingKeep.gone_items(l); } catch (Throwable t) { openhouse.mod.MissingKeep.oops(t); }
    }

    @Inject(method = { "save(Lcom/mojang/nbt/tags/ListTag;)Lcom/mojang/nbt/tags/ListTag;", "writeToNBT(Lcom/mojang/nbt/tags/ListTag;)Lcom/mojang/nbt/tags/ListTag;" }, at = @At("RETURN"), require = 0)
    private void openhouse$write(ListTag l, CallbackInfoReturnable<ListTag> cir) {
        try { openhouse.mod.MissingKeep.put_back(l, openhouse$gone); } catch (Throwable t) { openhouse.mod.MissingKeep.oops(t); }
    }
}
