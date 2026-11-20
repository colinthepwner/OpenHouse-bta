package openhouse.mod.mixin;

import net.minecraft.client.net.handler.PacketHandlerClient;
import net.minecraft.core.net.packet.PacketAddEntity;
import net.minecraft.core.net.packet.PacketMoveEntity;
import net.minecraft.core.net.packet.PacketTeleportEntity;
import openhouse.mod.MobSmooth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketHandlerClient.class, remap = false)
public abstract class MobPacketTimeMixin {
    @Inject(method = "handleMoveEntity", at = @At("HEAD"))
    private void openhouse$moveTime(PacketMoveEntity p, CallbackInfo ci) { MobSmooth.stamp(p.creationTimeMillis, p.rotating, p.x != 0 || p.y != 0 || p.z != 0); }

    @Inject(method = "handleMoveEntity", at = @At("RETURN"))
    private void openhouse$moveDone(PacketMoveEntity p, CallbackInfo ci) { MobSmooth.stamp(0, false); }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"))
    private void openhouse$tpTime(PacketTeleportEntity p, CallbackInfo ci) { MobSmooth.stamp(p.creationTimeMillis, true); }

    @Inject(method = "handleTeleportEntity", at = @At("RETURN"))
    private void openhouse$tpDone(PacketTeleportEntity p, CallbackInfo ci) { MobSmooth.stamp(0, false); }

    @Inject(method = "handleAddEntity", at = @At("HEAD"))
    private void openhouse$spawnTime(PacketAddEntity p, CallbackInfo ci) { MobSmooth.spawn = p.creationTimeMillis; }

    @Inject(method = "handleAddEntity", at = @At("RETURN"))
    private void openhouse$spawnDone(PacketAddEntity p, CallbackInfo ci) { MobSmooth.spawn = 0; }
}
