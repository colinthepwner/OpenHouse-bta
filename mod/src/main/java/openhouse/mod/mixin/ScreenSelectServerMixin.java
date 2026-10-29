package openhouse.mod.mixin;

import openhouse.mod.RoomCodePage;
import net.minecraft.client.gui.server.ScreenSelectServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenSelectServer.class, remap = false)
public abstract class ScreenSelectServerMixin {

    @Inject(method = "refreshPages", at = @At("TAIL"))
    private void openhouse$refreshPages(CallbackInfo ci) {
        RoomCodePage.refresh((ScreenSelectServer) (Object) this);
    }

    @Inject(method = "setupButtons", at = @At("TAIL"))
    private void openhouse$setupButtons(CallbackInfo ci) {
        ScreenSelectServer s = (ScreenSelectServer) (Object) this;
        if (((ScreenPagedAccessor) s).openhouse$getSelectedPage() != RoomCodePage.page()) return;
        s.rightOffset = 0;
        s.buttonAddServer.enabled = s.buttonAddServer.visible = false;
        s.buttonRefresh.enabled = s.buttonRefresh.visible = false;
        s.buttonCancel.xPosition = (s.width - 100) / 2;
    }
}
