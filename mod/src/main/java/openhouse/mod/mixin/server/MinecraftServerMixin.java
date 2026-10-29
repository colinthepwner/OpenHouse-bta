package openhouse.mod.mixin.server;

import openhouse.mod.server.ServerRoom;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftServer.class, remap = false)
public abstract class MinecraftServerMixin {
    @Inject(method = "startServer", at = @At("RETURN"))
    private void openhouse$started(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            try {
                ServerRoom.get().serverStarted((MinecraftServer) (Object) this);
            } catch (Throwable t) {

                ServerRoom.LOG.warn("room codes failed to start: {}", t.toString());
            }
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void openhouse$stopping(CallbackInfo ci) {
        try {
            ServerRoom.get().serverStopping();
        } catch (Throwable ignored) { }
    }
}
