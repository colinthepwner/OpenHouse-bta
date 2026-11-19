package openhouse.mod;

import net.minecraft.client.gui.Gui;

public final class Icons {
    private Icons() { }

    public static final int WHITE = 0xFFE0E0E0, SHADOW = 0xC0101014;

    public static void clipboard(Gui g, int bx, int by, int bw, int bh) { int x = bx + (bw - 9) / 2, y = by + (bh - 12) / 2; clip(g, x + 1, y + 1, SHADOW); clip(g, x, y, WHITE); }
    public static void copied(Gui g, int bx, int by, int bw, int bh) { int x = bx + (bw - 9) / 2, y = by + (bh - 12) / 2; done(g, x + 1, y + 1, SHADOW); done(g, x, y, WHITE); }

    public static void red(Gui g, net.minecraft.client.render.font.FontRenderer f, net.minecraft.client.gui.ButtonElement b) {
        if (b == null || !b.visible) return;
        g.drawRect(b.xPosition + 1, b.yPosition + 1, b.xPosition + b.width - 1, b.yPosition + b.height - 1, b.enabled ? 0x66D02020 : 0x40802020);
        g.drawStringCenteredShadow(f, b.displayString, b.xPosition + b.width / 2, b.yPosition + (b.height - 8) / 2, b.enabled ? 0xFFFFD8D8 : 0xFF907878);
    }

    public static void rewind(Gui g, int bx, int by, int bw, int bh, boolean on) { int x = bx + (bw - 9) / 2, y = by + (bh - 8) / 2; rew(g, x + 1, y + 1, SHADOW); rew(g, x, y, on ? WHITE : 0xFF707070); }

    static void rew(Gui g, int x, int y, int c) {
        for (int k = 0; k < 2; k++) {
            int ox = x + k * 4;
            for (int i = 0; i < 4; i++) g.drawRect(ox + i, y + 3 - i, ox + i + 1, y + 4 + i, c);
        }
    }

    static void clip(Gui g, int x, int y, int c) {
        g.drawRect(x + 2, y, x + 6, y + 1, c);
        g.drawRect(x, y + 1, x + 3, y + 2, c);
        g.drawRect(x + 5, y + 1, x + 8, y + 2, c);
        g.drawRect(x + 2, y + 2, x + 6, y + 3, c);
        g.drawRect(x, y + 2, x + 1, y + 10, c);
        g.drawRect(x + 7, y + 2, x + 8, y + 10, c);
        g.drawRect(x, y + 10, x + 8, y + 11, c);
        g.drawRect(x + 2, y + 5, x + 6, y + 6, c);
        g.drawRect(x + 2, y + 7, x + 5, y + 8, c);
    }

    static void done(Gui g, int x, int y, int c) {
        g.drawRect(x, y + 5, x + 1, y + 7, c);
        g.drawRect(x + 1, y + 6, x + 2, y + 8, c);
        g.drawRect(x + 2, y + 7, x + 3, y + 9, c);
        g.drawRect(x + 3, y + 6, x + 4, y + 8, c);
        g.drawRect(x + 4, y + 5, x + 5, y + 7, c);
        g.drawRect(x + 5, y + 4, x + 6, y + 6, c);
        g.drawRect(x + 6, y + 3, x + 7, y + 5, c);
        g.drawRect(x + 7, y + 2, x + 8, y + 4, c);
    }
}
