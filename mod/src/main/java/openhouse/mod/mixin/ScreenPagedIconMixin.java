package openhouse.mod.mixin;

import net.minecraft.client.gui.ItemElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.paged.ScreenPaged;
import net.minecraft.core.item.ItemStack;
import openhouse.mod.RoomCodePage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ScreenPaged.class, remap = false)
public abstract class ScreenPagedIconMixin {
    @Redirect(method = "drawPagesListItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ItemElement;render(Lnet/minecraft/core/item/ItemStack;II)V"), require = 0)
    private void openhouse$roomIcon(ItemElement items, ItemStack stack, int x, int y) {
        if (stack == null || stack != RoomCodePage.icon()) { items.render(stack, x, y); return; }
        Screen s = (Screen) (Object) this;
        net.minecraft.client.render.TextureManager tm = s.mc.textureManager;
        tm.bindTexture(tm.loadTexture("/assets/openhouse/gui/roomcode.png"));

        s.drawTexturedModalRect(x, y, 0, 0, 16, 16, 1.0 / 16, 1.0 / 16);
    }
}
