package openhouse.mod.mixin;

import openhouse.mod.LocalServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScreenConnecting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenConnecting.class, remap = false)
public abstract class ScreenConnectingMixin {
    @Unique private String openhouse$hostToken;

    @Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Ljava/lang/String;I)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"), require = 0)
    private void openhouse$ownWorld(Minecraft mc, String host, int port, CallbackInfo ci) {
        openhouse$hostToken = LocalServer.hostToken(host, port);

        openhouse.mod.OpenHouseMod.connectingTo(host, port);
    }

    @ModifyArg(method = "lambda$new$0",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/net/packet/PacketHandshake;<init>(Ljava/lang/String;)V"), require = 0)
    private String openhouse$hostHandshake(String name) {
        String t = openhouse$hostToken;
        return t != null ? t : name;
    }
}
