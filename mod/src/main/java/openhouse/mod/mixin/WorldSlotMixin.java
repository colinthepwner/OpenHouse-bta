package openhouse.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import openhouse.mod.OpenHostWorlds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.ScreenSelectWorld$WorldSlot", remap = false)
public abstract class WorldSlotMixin {
    @Inject(method = "renderItem", at = @At("TAIL"), require = 0)
    private void openhouse$frame(int row, int x, int y, int h, TessellatorGeneral t, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Screen s = mc == null ? null : mc.currentScreen;
            if (!(s instanceof OpenHostWorlds.Rows) || !((OpenHostWorlds.Rows) s).openhouse$openhost(row)) return;
            int x0 = s.width / 2 - 110, x1 = s.width / 2 + 110, y0 = y - 2, y1 = y + h + 2, c = 0xFFE8762A;
            s.drawRect(x0, y0, x1, y0 + 1, c);
            s.drawRect(x0, y1 - 1, x1, y1, c);
            s.drawRect(x0, y0, x0 + 1, y1, c);
            s.drawRect(x1 - 1, y0, x1, y1, c);
        } catch (Throwable ignored) { }
    }
}
