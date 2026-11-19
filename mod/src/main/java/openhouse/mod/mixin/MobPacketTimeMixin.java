package openhouse.mod.mixin;

import net.minecraft.client.net.handler.PacketHandlerClient;
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
    private void openhouse$moveTime(PacketMoveEntity p, CallbackInfo ci) { MobSmooth.stamp(p.creationTimeMillis); }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"))
    private void openhouse$tpTime(PacketTeleportEntity p, CallbackInfo ci) { MobSmooth.stamp(p.creationTimeMillis); }
}
