package openhouse.mod.mixin.server;

import openhouse.mod.server.ServerRoom;
import net.minecraft.core.net.packet.PacketHandshake;
import net.minecraft.core.net.packet.PacketLogin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.net.handler.PacketHandlerLogin;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketHandlerLogin.class, remap = false)
public abstract class PacketHandlerLoginMixin {

    @Unique private boolean openhouse$host;

    @Inject(method = "handleHandshake", at = @At("HEAD"), cancellable = true)
    private void openhouse$hostHandshake(PacketHandshake packet, CallbackInfo ci) {
        if (!ServerRoom.isHostToken(packet.username)) return;
        openhouse$host = true;
        ((PacketHandlerLogin) (Object) this).netManager.addToSendQueue(new PacketHandshake("-"));
        ci.cancel();
    }

    @Redirect(method = "handleLogin", at = @At(value = "FIELD", target = "Lnet/minecraft/server/MinecraftServer;onlineMode:Z", opcode = Opcodes.GETFIELD))
    private boolean openhouse$checkUnlessHost(MinecraftServer server) {
        return server.onlineMode && !openhouse$host;
    }

    @Inject(method = "doLogin", at = @At("HEAD"))
    private void openhouse$idForNoAccount(PacketLogin packet, CallbackInfo ci) {
        java.util.UUID id = ServerRoom.playerId(packet.username, packet.uuid, openhouse$host);
        if (id != packet.uuid) {
            ServerRoom.LOG.info("{} has no Minecraft account id - keeping their stuff under {}", packet.username, id);
            packet.uuid = id;
        }
    }
}
