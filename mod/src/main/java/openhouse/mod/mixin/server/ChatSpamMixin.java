package openhouse.mod.mixin.server;

import net.minecraft.core.net.packet.PacketMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.handler.PacketHandlerServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketHandlerServer.class, remap = false)
public abstract class ChatSpamMixin {
    @Shadow private PlayerServer playerEntity;
    @Shadow private MinecraftServer mcServer;
    @Unique private long openhouse$spam, openhouse$spamAt, openhouse$lastAt, openhouse$toldAt;
    @Unique private String openhouse$last;

    @Inject(method = "handleMessage", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$spam(PacketMessage packet, CallbackInfo ci) {
        try {
            PlayerServer p = playerEntity;
            if (p == null) return;
            long now = System.currentTimeMillis();
            openhouse$spam = Math.max(0, openhouse$spam - (now - openhouse$spamAt) / 50);
            openhouse$spamAt = now;
            openhouse$spam += 20;

            String text = packet.message;
            try {
                if (packet.encrypted) text = net.minecraft.core.util.helper.AES.decrypt(packet.message, (java.security.Key) net.minecraft.core.util.helper.AES.keyChain.get(p.username));
            } catch (Throwable ignored) { }
            String norm = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
            boolean command = norm.startsWith("/");
            boolean same = norm.equals(openhouse$last);
            if (!command && !norm.isEmpty() && openhouse$lastAt > 0 && now - openhouse$lastAt < (same ? 3000 : 1000)) {
                ci.cancel();
                if (now - openhouse$toldAt > 3000) {
                    openhouse$toldAt = now;
                    openhouse.mod.server.Say.to(p, net.minecraft.core.net.command.TextFormatting.LIGHT_GRAY + (same ? "Slow down, that's the same message again." : "Slow down a little."));
                }
                return;
            }
            if (!command) { openhouse$last = norm; openhouse$lastAt = now; }
            if (openhouse$spam <= 200) return;
            if (p.username != null && p.username.equalsIgnoreCase(openhouse.mod.server.ServerRoom.lanHostName)) return;
            if (p.uuid != null && mcServer != null && mcServer.playerList != null && mcServer.playerList.isOp(p.uuid)) return;
            ci.cancel();
            openhouse.mod.server.ServerRoom.LOG.info("{} was kicked for chat spam", p.username);
            ((PacketHandlerServer) (Object) this).kickPlayer("Kicked for spamming chat.");
        } catch (Throwable ignored) { }
    }
}
