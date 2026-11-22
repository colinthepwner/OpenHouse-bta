package openhouse.mod.mixin;

import com.mojang.nbt.tags.CompoundTag;
import net.minecraft.core.world.World;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.ChunkLoaderLegacy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkLoaderLegacy.class)
public abstract class KeepChunkMixin {
    @Inject(method = "loadChunkIntoWorldFromCompound", at = @At("HEAD"), require = 0)
    private static void openhouse$loading(World world, CompoundTag tag, CallbackInfoReturnable<Chunk> cir) {
        openhouse.mod.MissingKeep.LOADING.set(tag);
    }

    @Inject(method = "storeChunkInCompound", at = @At("RETURN"), require = 0)
    private static void openhouse$stored(Chunk chunk, World world, CompoundTag tag, CallbackInfo ci) {
        try {
            openhouse.mod.MissingKeep.store(chunk, tag);
        } catch (Throwable t) {
            openhouse.mod.MissingKeep.oops(t);
        }
    }
}
