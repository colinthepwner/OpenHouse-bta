package openhouse.mod.mixin.server;

import openhouse.mod.server.ServerRoom;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.chunk.provider.ChunkProviderServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;

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

    @Inject(method = "doTick", at = @At("HEAD"), require = 0)
    private void openhouse$tick(CallbackInfo ci) {
        if (ServerRoom.OPENHOST) openhouse.mod.server.HostWindow.TICKS.incrementAndGet();
        if (ServerRoom.STATS_MS > 0) ServerRoom.statsTickStart();
    }

    @Inject(method = "doTick", at = @At("RETURN"), require = 0)
    private void openhouse$tickEnd(CallbackInfo ci) {
        if (ServerRoom.STATS_MS > 0) ServerRoom.statsTickEnd((MinecraftServer) (Object) this);
    }

    @Redirect(method = "commandLineParser", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/net/command/CommandManager;execute(Ljava/lang/String;Lnet/minecraft/core/net/command/CommandSource;)I"), require = 0)
    private int openhouse$lazy(net.minecraft.core.net.command.CommandManager commands, String command, net.minecraft.core.net.command.CommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (ServerRoom.OPENHOST) {
            try {
                openhouse.mod.server.LazyCommands.Result r = openhouse.mod.server.LazyCommands.resolve((MinecraftServer) (Object) this, command);
                if (r != null && !r.command.equals(command)) {
                    ServerRoom.LOG.info("{}  →  {}", ServerRoom.hideSecret(command.trim()), ServerRoom.hideSecret(r.command));
                    command = r.command;
                }
            } catch (Throwable ignored) { }
        }
        return commands.execute(command, source);
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Ljava/lang/System;exit(I)V"), require = 0)
    private void openhouse$exitCode(int code) {
        System.exit(ServerRoom.restartRequested && ServerRoom.OPENHOST ? openhouse.mod.OpenHost.RESTART_EXIT : code);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void openhouse$stopping(CallbackInfo ci) {
        try {
            ServerRoom.get().serverStopping();
        } catch (Throwable ignored) { }
    }

    @Inject(method = "initWorld", at = @At("HEAD"), require = 0)
    private void openhouse$waitForWorld(CallbackInfo ci) {
        ServerRoom.waitForWorld();
    }

    @Redirect(method = "initWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/chunk/provider/ChunkProviderServer;prepareChunk(II)Lnet/minecraft/core/world/chunk/Chunk;"), require = 0)
    private Chunk openhouse$spawnArea(ChunkProviderServer chunks, int x, int z) {
        return ServerRoom.LAN ? null : chunks.prepareChunk(x, z);
    }

    @Redirect(method = "doTick", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 0)
    private void openhouse$tickError(Logger log, String msg, Object a, Object b) {
        log.error(msg, a, b);
        if (b instanceof Throwable) ServerRoom.lastTickError = (Throwable) b;
    }

    @Redirect(method = "doTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initiateShutdown()V"), require = 0)
    private void openhouse$tickCrash(MinecraftServer self) {
        try {
            ServerRoom.get().crashed(ServerRoom.lastTickError);
        } catch (Throwable ignored) { }
        self.initiateShutdown();
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"), require = 0)
    private void openhouse$unexpected(Logger log, String msg, Throwable t) {
        log.error(msg, t);
        if (!ServerRoom.LAN || !"Unexpected exception".equals(msg)) return;
        try {
            ServerRoom.get().crashed(t);
        } catch (Throwable ignored) { }
        ((MinecraftServer) (Object) this).initiateShutdown();
    }
}
