package openhouse.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.verse.e4btavoice.client.VoiceClient", remap = false)
public abstract class E4VoiceQuietMixin {
    @Shadow(remap = false) private boolean recording;
    @Shadow(remap = false) private void setRecording(boolean enabled) { }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void openhouse$quiet(CallbackInfo ci) {
        if (!openhouse.mod.voice.Voice.active()) return;
        try { if (recording) setRecording(false); } catch (Throwable ignored) { }
        ci.cancel();
    }

    @Inject(method = "receiveRelayed", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void openhouse$deaf(byte[] packet, CallbackInfo ci) {
        if (openhouse.mod.voice.Voice.active()) ci.cancel();
    }
}
