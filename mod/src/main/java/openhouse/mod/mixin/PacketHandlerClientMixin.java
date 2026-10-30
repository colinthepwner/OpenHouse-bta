package openhouse.mod.mixin;

import net.minecraft.client.net.handler.PacketHandlerClient;
import openhouse.mod.OpenHouseMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = PacketHandlerClient.class, remap = false)
public abstract class PacketHandlerClientMixin {

    @Redirect(method = "handleHandshake", at = @At(value = "INVOKE", target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"), require = 0)
    private boolean openhouse$roomDecides(String answer, String ok) {
        if (answer != null && answer.equalsIgnoreCase(ok)) return true;
        if (!OpenHouseMod.connectingToRoom()) return false;
        OpenHouseMod.LOG.info("Mojang didn't accept this session (\"{}\") - joining the room anyway, its host decides", answer);
        return true;
    }
}
