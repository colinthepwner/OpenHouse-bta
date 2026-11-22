package openhouse.mod.mixin;

import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chunk.class)
public abstract class KeepFixMixin {
    @Shadow @Final protected ChunkSection[] sections;
    @Unique private short[][] openhouse$was;

    @Inject(method = "fixMissingBlocks", at = @At("HEAD"), require = 0)
    private void openhouse$before(CallbackInfo ci) {
        try {
            short[][] b = new short[16][];
            for (int i = 0; i < 16 && i < sections.length; i++) if (sections[i] != null) b[i] = sections[i].blocks;
            openhouse$was = openhouse.mod.MissingKeep.before(b);
        } catch (Throwable t) { openhouse$was = null; }
    }

    @Inject(method = "fixMissingBlocks", at = @At("RETURN"), require = 0)
    private void openhouse$after(CallbackInfo ci) {
        short[][] was = openhouse$was;
        openhouse$was = null;
        if (was == null) return;
        try {
            short[][] now = new short[16][];
            byte[][] meta = new byte[16][];
            for (int i = 0; i < 16 && i < sections.length; i++) if (sections[i] != null) {
                now[i] = sections[i].blocks;
                meta[i] = sections[i].data == null ? null : sections[i].data.data;
            }
            openhouse.mod.MissingKeep.after(this, was, now, meta);
        } catch (Throwable t) {
            openhouse.mod.MissingKeep.LOADING.remove();
        }
    }
}
