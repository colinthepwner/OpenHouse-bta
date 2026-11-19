package openhouse.mod.mixin;

import net.minecraft.client.render.shader.ShadersRenderer;
import net.minecraft.client.render.shader.framebuffer.FrameBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ShadersRenderer.class, remap = false)
public interface ShadersRendererAccessor {
    @Accessor("worldFrameBuffer")
    FrameBuffer openhouse$world_fb();
}
