package openhouse.mod.mixin;

import openhouse.mod.voice.GameRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.betterthanadventure.sound.SourceLWJGL3OpenAL", remap = false)
public abstract class GameSoundRefMixin {
    @Inject(method = "play", at = @At("TAIL"), require = 0)
    private void openhouse$ref(CallbackInfo ci) {
        GameRef.played(this);
    }
}
