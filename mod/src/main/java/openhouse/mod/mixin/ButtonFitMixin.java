package openhouse.mod.mixin;

import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.render.font.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ButtonElement.class, remap = false)
public abstract class ButtonFitMixin {
    @Redirect(method = "drawButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ButtonElement;drawStringCenteredShadow(Lnet/minecraft/client/render/font/FontRenderer;Ljava/lang/CharSequence;III)V"), require = 0)
    private void openhouse$fit(ButtonElement self, FontRenderer f, CharSequence text, int cx, int y, int color) {
        try {
            String s = text == null ? "" : text.toString();
            int room = self.width - 6;
            if (s.isEmpty() || room <= 0 || f.stringWidth(s) <= room) { self.drawStringCenteredShadow(f, text, cx, y, color); return; }
            String plain = s.replaceAll("§.", "");

            int last = 0;
            while (last < plain.length() && f.stringWidth(plain.substring(last)) > room) last++;

            long step = 300, hold = 1200, len = last * step;
            long t = System.currentTimeMillis() % (2 * (len + hold));
            int start;
            if (t < hold) start = 0;
            else if (t < hold + len) start = (int) ((t - hold) / step);
            else if (t < 2 * hold + len) start = last;
            else start = last - (int) ((t - 2 * hold - len) / step);
            start = Math.max(0, Math.min(last, start));
            int end = start;
            while (end < plain.length() && f.stringWidth(plain.substring(start, end + 1)) <= room) end++;
            self.drawStringCenteredShadow(f, plain.substring(start, end), cx, y, color);
        } catch (Throwable t) {
            self.drawStringCenteredShadow(f, text, cx, y, color);
        }
    }
}
