package openhouse.mod.mixin;

import openhouse.mod.OpenHouseMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftMixin {

    @Inject(method = "onMinecraftCrash", at = @At("HEAD"), require = 0)
    private void openhouse$crash(net.minecraft.core.UnexpectedThrowable t, CallbackInfo ci) {
        try { if (!((Minecraft) (Object) this).hasCrashed) openhouse.mod.CrashReports.crash(t.description, t.exception); } catch (Throwable ignored) { }
    }

    @Inject(method = "startWorld", at = @At("HEAD"))
    private void openhouse$startWorld(String worldDirName, CallbackInfo ci) {
        OpenHouseMod.singlePlayerWorld = worldDirName;
    }

    @Inject(method = "startWorld", at = @At(value = "INVOKE", target = "Ljava/lang/System;gc()V"))
    private void openhouse$freshRegions(String worldDirName, CallbackInfo ci) {
        openhouse.mod.RegionFiles.release();

        openhouse.mod.WorldGuard.beforeOpen((Minecraft) (Object) this, worldDirName);
    }

    @org.spongepowered.asm.mixin.Unique private String openhouse$leaving;

    @Inject(method = "changeWorld(Lnet/minecraft/client/world/WorldClient;Ljava/lang/String;Lnet/minecraft/core/entity/player/Player;)V", at = @At("HEAD"), require = 0)
    private void openhouse$leave_head(net.minecraft.client.world.WorldClient w, String title, net.minecraft.core.entity.player.Player p, CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        openhouse$leaving = w == null && mc.currentWorld != null && !mc.currentWorld.isClientSide && !openhouse.mod.LocalServer.hosting()
                ? OpenHouseMod.singlePlayerWorld : null;
    }

    @Inject(method = "changeWorld(Lnet/minecraft/client/world/WorldClient;Ljava/lang/String;Lnet/minecraft/core/entity/player/Player;)V", at = @At("RETURN"), require = 0)
    private void openhouse$leave_done(net.minecraft.client.world.WorldClient w, String title, net.minecraft.core.entity.player.Player p, CallbackInfo ci) {
        String left = openhouse$leaving;
        openhouse$leaving = null;
        if (left != null) openhouse.mod.WorldGuard.left(left);
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void openhouse$tick(CallbackInfo ci) {
        OpenHouseMod.clientTick((Minecraft) (Object) this);
    }
}
