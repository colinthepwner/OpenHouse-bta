package openhouse.mod.mixin;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.server.ScreenSelectServer;
import net.minecraft.core.lang.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenSelectServer.class, remap = false)
public abstract class ScreenSelectServerRoomMixin {
    private static final int ROOM_BTN = 7310;

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void openhouse$room_btn(CallbackInfo ci) {
        if (!openhouse.gate.Gate.v7()) return;
        Screen s = (Screen) (Object) this;

        s.buttons.add(new ButtonElement(ROOM_BTN, s.width - 106, 6, 100, 20, I18n.getInstance().translateKey("gui.openhouse.page.title")));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$room_click(ButtonElement b, CallbackInfo ci) {
        if (!openhouse.gate.Gate.v7() || b.id != ROOM_BTN) return;
        ci.cancel();
        Screen s = (Screen) (Object) this;
        s.mc.displayScreen(new openhouse.mod.ScreenRoomCode(s));
    }
}
