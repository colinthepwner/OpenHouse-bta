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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlayerList.class, remap = false)
public abstract class PlayerListMixin {
    @Shadow public List<PlayerServer> playerEntities;

    @Unique private String openhouse$hostName;
    @Unique private UUID openhouse$hostId;

    @Inject(method = "getPlayerForLogin", at = @At("HEAD"), cancellable = true)
    private void openhouse$nameTaken(PacketHandlerLogin handler, String name, UUID uuid, CallbackInfoReturnable<PlayerServer> cir) {
        if (!ServerRoom.LAN || name == null) return;
        LoginFlags f = (LoginFlags) handler;
        if (f.openhouse$isHost()) {
            openhouse$hostName = name;
            openhouse$hostId = uuid;
            ServerRoom.lanHostName = name;
            return;
        }
        MinecraftServer server = MinecraftServer.getInstance();
        boolean verified = server != null && server.onlineMode && !f.openhouse$noAccount();
        for (PlayerServer p : playerEntities) {
            if (!name.equalsIgnoreCase(p.username) && (uuid == null || !uuid.equals(p.uuid))) continue;
            boolean host = p.username.equalsIgnoreCase(openhouse$hostName) && p.uuid != null && p.uuid.equals(openhouse$hostId);
            if (verified && !host) continue;
            if (host && verified) {
                ServerRoom.LOG.info("{} tried to join on the host's own account while the host is playing - turned away", name);
                handler.kickUser("The host is playing on this account. Join with another one.");
            } else {
                ServerRoom.LOG.info("{} tried to join, but someone called {} is already playing", name, p.username);
                handler.kickUser("Someone called " + p.username + " is already playing here. Pick another name.");
            }
            cir.setReturnValue(null);
            return;
        }
    }

    @Unique private static final int[][] openhouse$around = { {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1} };

    @Inject(method = "playerLoggedIn", at = @At("HEAD"))
    private void openhouse$free_spot(PlayerServer p, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {

        if (p.chatColor == 0) p.chatColor = openhouse.mod.NameColor.code_of(p.username);
        try {
            MinecraftServer server = MinecraftServer.getInstance();
            net.minecraft.core.world.World w = server == null ? null : server.getDimensionWorld(p.dimension);
            if (w == null || w.getWorldType().hasCeiling()) return;
            w.getChunkProvider().prepareChunk((int) p.x >> 4, (int) p.z >> 4);
            if (w.getCollidingSolidBlockBoundingBoxes(p, p.bb).isEmpty()) return;
            double ox = p.x, oy = p.y, oz = p.z;

            double feet = p.bb.minY, base = Math.floor(feet + 0.5);
            double[] ys = { base, base + 1, base - 1 };
            for (int[] o : openhouse$around) {
                double x = o[0] == 0 ? ox : Math.floor(ox) + o[0] + 0.5, z = o[1] == 0 ? oz : Math.floor(oz) + o[1] + 0.5;
                for (double fy : ys) {
                    p.setPos(x, oy + (fy - feet), z);
                    if (w.getCollidingSolidBlockBoundingBoxes(p, p.bb).isEmpty()) {
                        ServerRoom.LOG.info("{} logged in inside a block at {} {} {}: moved to {} {} {}", p.username,
                                (int) Math.floor(ox), (int) Math.floor(oy), (int) Math.floor(oz), (int) Math.floor(x), (int) Math.floor(p.y), (int) Math.floor(z));
                        return;
                    }
                }
            }
            p.setPos(ox, oy, oz);
        } catch (Throwable t) {
            ServerRoom.LOG.warn("couldn't check where {} logs in: {}", p.username, t.toString());
        }
    }
}
