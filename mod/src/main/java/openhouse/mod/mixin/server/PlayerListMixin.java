package openhouse.mod.mixin.server;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.PlayerList;
import net.minecraft.server.net.handler.PacketHandlerLogin;
import openhouse.mod.server.LoginFlags;
import openhouse.mod.server.ServerRoom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlayerList.class, remap = false)
public abstract class PlayerListMixin {
    @Shadow public List<PlayerServer> playerEntities;

    @Inject(method = "getPlayerForLogin", at = @At("HEAD"), cancellable = true)
    private void openhouse$nameTaken(PacketHandlerLogin handler, String name, UUID uuid, CallbackInfoReturnable<PlayerServer> cir) {
        if (!ServerRoom.LAN || name == null) return;
        LoginFlags f = (LoginFlags) handler;
        if (f.openhouse$isHost()) return;
        MinecraftServer server = MinecraftServer.getInstance();
        if (server != null && server.onlineMode && !f.openhouse$noAccount()) return;
        for (PlayerServer p : playerEntities) {
            if (name.equalsIgnoreCase(p.username) || (uuid != null && uuid.equals(p.uuid))) {
                ServerRoom.LOG.info("{} tried to join, but someone called {} is already playing", name, p.username);
                handler.kickUser("Someone called " + p.username + " is already playing here - pick another name.");
                cir.setReturnValue(null);
                return;
            }
        }
    }
}
