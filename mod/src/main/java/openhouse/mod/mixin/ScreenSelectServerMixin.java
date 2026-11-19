package openhouse.mod.mixin;

import openhouse.mod.NearbyRooms;
import openhouse.mod.RoomCodePage;
import openhouse.mod.ScreenRoomJoin;
import net.minecraft.client.gui.server.ServerEntry;
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

    @Inject(method = "joinEntry", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$join_room(ServerEntry e, CallbackInfo ci) {
        String code = NearbyRooms.code(e);
        if (code == null) return;
        ci.cancel();
        ScreenSelectServer s = (ScreenSelectServer) (Object) this;
        s.mc.displayScreen(new ScreenRoomJoin(s, code));
    }
}
