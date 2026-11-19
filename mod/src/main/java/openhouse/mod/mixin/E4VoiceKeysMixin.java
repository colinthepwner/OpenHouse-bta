package openhouse.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.verse.e4btavoice.client.VoiceGameOptions", remap = false)
public abstract class E4VoiceKeysMixin {
    @Inject(method = "handleSettingsOverlayKey", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void openhouse$ours(CallbackInfo ci) {
        if (openhouse.mod.voice.Voice.active()) ci.cancel();
    }
}
