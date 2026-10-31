package openhouse.mod.mixin;

import net.minecraft.client.net.handler.PacketHandlerClient;
import net.minecraft.core.data.registry.Registries;
import net.minecraft.core.net.packet.PacketLogin;
import openhouse.mod.OpenHouseMod;
import openhouse.mod.ServerRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketHandlerClient.class, remap = false)
public abstract class PacketHandlerClientMixin {

    @Redirect(method = "handleHandshake", at = @At(value = "INVOKE", target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"), require = 0)
    private boolean openhouse$roomDecides(String answer, String ok) {
        if (answer != null && answer.equalsIgnoreCase(ok)) return true;
        if (!OpenHouseMod.connectingToRoom()) return false;
        OpenHouseMod.LOG.info("Mojang didn't accept this session (\"{}\") - joining the room anyway, its host decides", answer);
        return true;
    }

    @Inject(method = "handleLogin", at = @At("HEAD"), require = 0)
    private void openhouse$beforeLogin(PacketLogin packet, CallbackInfo ci) {
        ServerRecipes.remember(Registries.RECIPES);
    }
}
