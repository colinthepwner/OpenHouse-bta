package openhouse.mod.mixin.server;

import net.minecraft.core.net.packet.Packet;
import net.minecraft.core.net.packet.PacketPlayerAction;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.PlayerList;
import net.minecraft.server.net.handler.PacketHandlerServer;
import openhouse.mod.server.Seamless;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketHandlerServer.class, remap = false)
public abstract class SeamlessMixin {
    @Shadow private PlayerServer playerEntity;
    @Unique private int openhouse$dx, openhouse$dy, openhouse$dz;
    @Unique private net.minecraft.core.util.helper.Side openhouse$dside;
    @Unique private long openhouse$digAt;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), require = 0)
    private void openhouse$dig(PacketPlayerAction a, CallbackInfo ci) {
        if (a.action == 0 || a.action == 1) {
            openhouse$dx = a.xPosition; openhouse$dy = a.yPosition; openhouse$dz = a.zPosition; openhouse$dside = a.side;
            openhouse$digAt = System.currentTimeMillis();
        } else if (a.action == 2) openhouse$digAt = 0;
    }

    @Inject(method = "handleErrorMessage", at = @At("HEAD"), require = 0)
    private void openhouse$dropped(String msg, Object[] args, CallbackInfo ci) {
        try {

            if (openhouse$digAt > 0 && System.currentTimeMillis() - openhouse$digAt < 500 && playerEntity != null)
                Seamless.finishDig(playerEntity.playerController, openhouse$dx, openhouse$dy, openhouse$dz, openhouse$dside);
        } catch (Throwable ignored) { }
    }

    @Redirect(method = "handleErrorMessage", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/net/PlayerList;sendPacketToAllPlayers(Lnet/minecraft/core/net/packet/Packet;)V"), require = 0)
    private void openhouse$leaveLater(PlayerList list, Packet p) {
        try { Seamless.lost(list, p); } catch (Throwable t) { list.sendPacketToAllPlayers(p); }
    }
}
