package openhouse.mod.mixin;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenPause;
import openhouse.mod.LocalServer;
import openhouse.mod.ScreenRoomText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Screen.class, remap = false)
public abstract class ScreenRightClickMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$right_click(int mx, int my, int btn, CallbackInfo ci) {
        if (btn != 1 || !((Object) this instanceof ScreenPause) || !LocalServer.hosting()) return;
        Screen s = (Screen) (Object) this;
        for (ButtonElement b : s.buttons) {
            if (b.id != 7702 || !b.visible || !b.enabled) continue;
            if (mx < b.xPosition || mx >= b.xPosition + b.width || my < b.yPosition || my >= b.yPosition + b.height) continue;
            ci.cancel();
            s.mc.sndManager.playSound("random.click", net.minecraft.core.sound.SoundCategory.GUI_SOUNDS, 1.0F, 1.0F);
            ScreenRoomText.askCode(s);
            return;
        }
    }
}
