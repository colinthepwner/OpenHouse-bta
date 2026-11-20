package openhouse.mod.mixin;

import openhouse.mod.OpenHouseMod;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenMainMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenMainMenu.class, remap = false)
public abstract class ScreenMainMenuMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void openhouse$init(CallbackInfo ci) {
        OpenHouseMod.titleScreen((ScreenMainMenu) (Object) this);
    }

    @Unique
    private String openhouse$quiet_text() {
        openhouse.Update.Release r = openhouse.mod.Updater.quiet;
        return r == null ? null : net.minecraft.core.lang.I18n.getInstance().translateKeyAndFormat("gui.openhouse.update.quiet", r.version);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void openhouse$quiet(int mx, int my, float pt, CallbackInfo ci) {
        String s = openhouse$quiet_text();
        if (s == null) return;
        Screen me = (Screen) (Object) this;
        int w = me.fontRenderer.stringWidth(s);
        boolean over = mx >= 2 && mx < 2 + w && my >= 12 && my < 21;
        me.drawStringShadow(me.fontRenderer, s, 2, 12, over ? 0xFFFFA0 : 0xE0C040);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void openhouse$quiet_click(int mx, int my, int btn, CallbackInfo ci) {
        String s = openhouse$quiet_text();
        if (s == null || btn != 0) return;
        Screen me = (Screen) (Object) this;
        if (mx < 2 || mx >= 2 + me.fontRenderer.stringWidth(s) || my < 12 || my >= 21) return;
        ci.cancel();
        me.mc.displayScreen(new openhouse.mod.ScreenUpdate(me, openhouse.mod.Updater.quiet, null));
    }
}
