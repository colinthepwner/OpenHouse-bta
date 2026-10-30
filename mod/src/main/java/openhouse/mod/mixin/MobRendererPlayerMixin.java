package openhouse.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.ImageParser;
import net.minecraft.client.render.TextureManager;
import net.minecraft.client.render.entity.MobRendererPlayer;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.core.entity.player.Player;
import openhouse.mod.Capes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MobRendererPlayer.class, remap = false)
public abstract class MobRendererPlayerMixin {

    @Unique private Player openhouse$player;

    @Inject(method = "renderAdditional(Lnet/minecraft/client/render/tessellator/TessellatorGeneral;Lnet/minecraft/core/entity/player/Player;F)V",
            at = @At("HEAD"))
    private void openhouse$who(TessellatorGeneral t, Player player, float partialTick, CallbackInfo ci) {
        openhouse$player = player;
    }

    @Redirect(method = "renderAdditional(Lnet/minecraft/client/render/tessellator/TessellatorGeneral;Lnet/minecraft/core/entity/player/Player;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/MobRendererPlayer;bindDownloadableTexture(Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/client/render/ImageParser;)Z"),
            require = 0)
    private boolean openhouse$cape(MobRendererPlayer self, String url, String fallback, ImageParser parser) {
        Player p = openhouse$player;
        if (p != null && Capes.hasOpenHouseCape(p.username)) {
            TextureManager tm = Minecraft.getMinecraft().textureManager;
            tm.bindTexture(tm.loadTexture(Capes.OH_CAPE_TEXTURE));
            return true;
        }
        return self.bindDownloadableTexture(url, fallback, parser);
    }
}
