package openhouse.mod.mixin.server;

import java.util.UUID;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.world.ServerPlayerController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerServer.class)
public abstract class PlayerServerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void openhouse$groundUnderSpawn(MinecraftServer server, World world, String username, UUID uuid, ServerPlayerController controller, CallbackInfo ci) {
        try {

            if (world == null || world.getWorldType().hasCeiling()) return;
            Entity self = (Entity) (Object) this;
            int x = (int) Math.floor(self.x), z = (int) Math.floor(self.z);
            int y = world.findTopSolidBlock(x, z);
            if (y <= 0) {
                TilePos spawn = world.getSpawnPoint();
                x = spawn.x;
                z = spawn.z;
                y = world.findTopSolidBlock(x, z);
                if (y <= 0) y = spawn.y;
            }
            self.moveTo(x + 0.5, y, z + 0.5, self.yRot, self.xRot);
        } catch (RuntimeException e) {

        }
    }
}
