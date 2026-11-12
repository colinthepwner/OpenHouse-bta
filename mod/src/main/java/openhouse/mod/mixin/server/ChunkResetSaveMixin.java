package openhouse.mod.mixin.server;

import java.util.List;
import java.util.Map;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.ChunkPosc;
import net.minecraft.server.world.chunk.provider.ChunkProviderServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkProviderServer.class, remap = false)
public abstract class ChunkResetSaveMixin {
    @Shadow @Final private Map<ChunkPos, Chunk> chunkMap;
    @Shadow @Final private List<Chunk> chunkList;

    @Inject(method = "regenerateChunk", at = @At("RETURN"), require = 0)
    private void openhouse$keepReset(ChunkPosc pos, CallbackInfo ci) {
        Chunk c = chunkMap.get(new ChunkPos(pos));
        if (c == null) return;
        if (!chunkList.contains(c)) chunkList.add(c);
        c.setChunkModified();
    }
}
