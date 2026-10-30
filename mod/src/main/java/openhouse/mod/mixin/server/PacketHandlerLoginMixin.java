package openhouse.mod.mixin.server;

import openhouse.mod.server.LoginFlags;
import openhouse.mod.server.ServerRoom;
import net.minecraft.core.net.packet.PacketHandshake;
import net.minecraft.core.net.packet.PacketLogin;
import net.minecraft.core.util.helper.UUIDHelper;
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
public abstract class PacketHandlerLoginMixin implements LoginFlags {

    @Unique private boolean openhouse$host;
    @Unique private volatile PacketLogin openhouse$checking;
    @Unique private volatile boolean openhouse$noAccount;

    @Override public boolean openhouse$isHost() { return openhouse$host; }
    @Override public boolean openhouse$noAccount() { return openhouse$noAccount; }

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

    @Inject(method = "lambda$handleLogin$0", at = @At("HEAD"), require = 0)
    private void openhouse$checking(PacketLogin packet, CallbackInfo ci) {
        openhouse$checking = packet;
    }

    @Redirect(method = "lambda$handleLogin$0", at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"), require = 0)
    private boolean openhouse$verifiedOrNoAccount(String answer, Object yes) {
        if (answer != null && answer.equals(yes)) return true;
        PacketLogin p = openhouse$checking;
        if (!ServerRoom.LAN || p == null || p.username == null) return false;
        Boolean account = ServerRoom.mojangAccount(p.username);
        if (account == null || account) return false;
        if (p.uuid == null) p.uuid = new java.util.UUID(0, 0);
        openhouse$noAccount = true;
        ServerRoom.LOG.info("{} has no Minecraft account - letting them in without the account check", p.username);
        return true;
    }

    @Redirect(method = "lambda$handleLogin$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/util/helper/UUIDHelper;getUUIDFromName(Ljava/lang/String;)Ljava/util/UUID;"), require = 0)
    private java.util.UUID openhouse$idOfNoAccount(String name) {
        PacketLogin p = openhouse$checking;
        if (openhouse$noAccount && p != null) return p.uuid;
        return UUIDHelper.getUUIDFromName(name);
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
