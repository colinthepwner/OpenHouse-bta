package openhouse.mod.mixin;

import java.util.LinkedList;
import openhouse.mod.voice.GameRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.betterthanadventure.sound.ChannelLWJGL3OpenAL", remap = false)
public abstract class GameStreamRefMixin {
    @Inject(method = "queueBuffer", at = @At("HEAD"), require = 0)
    private void openhouse$queued(byte[] data, CallbackInfoReturnable<Boolean> ci) {
        GameRef.streamed(this, data);
    }

    @SuppressWarnings("rawtypes")
    @Inject(method = "preLoadBuffers", at = @At("HEAD"), require = 0)
    private void openhouse$preloaded(LinkedList list, CallbackInfoReturnable<Boolean> ci) {
        if (list == null) return;
        for (Object o : list) if (o instanceof byte[]) GameRef.streamed(this, (byte[]) o);
    }
}
