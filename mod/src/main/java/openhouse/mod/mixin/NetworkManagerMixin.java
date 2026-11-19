package openhouse.mod.mixin;

import java.util.List;
import net.minecraft.core.net.NetworkManager;
import net.minecraft.core.net.handler.PacketHandler;
import net.minecraft.core.net.packet.PacketCustomPayload;
import openhouse.mod.voice.VoiceWire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = NetworkManager.class, remap = false)
public abstract class NetworkManagerMixin {
    @Shadow private PacketHandler packetListener;

    @Redirect(method = "readPacket", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"), require = 0)
    private boolean openhouse$voice(List<Object> list, Object p) {
        if (p instanceof PacketCustomPayload && VoiceWire.CHANNEL.equals(((PacketCustomPayload) p).channel)) {
            byte[] d = ((PacketCustomPayload) p).data;
            PacketHandler h = packetListener;
            VoiceWire.Sink s = h == null ? null : h.isServerHandler() ? VoiceWire.server : VoiceWire.client;
            if (d != null && s != null) {
                try { s.got(h, d); } catch (Throwable ignored) { }
            }
            return true;
        }
        return list.add(p);
    }
}
