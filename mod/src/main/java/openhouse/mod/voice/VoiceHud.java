package openhouse.mod.voice;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.render.font.FontRenderer;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.lang.I18n;

public final class VoiceHud {
    private VoiceHud() { }

    public static final int IDLE = 0xFF8C8A96, GOLD = 0xFFFFC94A, RED = 0xFFC8505E, SHADOW = 0xC0101014;
    static final int MAX_LISTED = 3;
    static final long PULSE_MS = 2400;
    static final int DEAF_PULSES = 3;

    private static Object shown_world;
    private static long shown_at;

    public static void draw(Gui g, Minecraft mc, int w, int h) {
        if (!Voice.active() || !someone_here(mc)) return;
        FontRenderer font = mc.font;
        I18n t = I18n.getInstance();
        int x = 5, y = h - 5 - 11;
        boolean muted = VoiceCfg.muted;
        String prob = muted ? null : Voice.problem();

        if (mc.currentWorld != shown_world) { shown_world = mc.currentWorld; shown_at = System.currentTimeMillis(); }
        if (prob != null && prob.equals(t.translateKey("gui.openhouse.voice.hud.deaf")) && System.currentTimeMillis() - shown_at > DEAF_PULSES * PULSE_MS) prob = null;
        boolean off = muted || prob != null;
        int c = off ? pulse(RED) : Voice.talking() ? GOLD : IDLE;
        mic(g, x + 1, y + 1, SHADOW, off);
        mic(g, x, y, c, off);
        String label = muted ? t.translateKey("gui.openhouse.voice.hud.muted") : prob;
        if (label != null) g.drawStringShadow(font, label, x + 12, y + 2, c);
        List<Player> talk = Voice.speaking(mc);
        int ty = y - 13;
        long now = System.currentTimeMillis();
        for (int i = 0; i < talk.size() && i < MAX_LISTED; i++) {
            Player p = talk.get(i);
            if (i == MAX_LISTED - 1 && talk.size() > MAX_LISTED) {
                g.drawStringShadow(font, t.translateKeyAndFormat("gui.openhouse.voice.hud.more", talk.size() - i), x + 12, ty, 0xFFC0C0C0);
                break;
            }
            for (int b = 0; b < 3; b++) {
                float l = 0.35F + 0.65F * Voice.loudness(p.id);
                int bh = 1 + (int) (6 * l * (0.5 + 0.5 * Math.sin(now / 90.0 + b * 2.1 + p.id)));
                g.drawRect(x + 1 + b * 3, ty + 8 - bh, x + 3 + b * 3, ty + 8, GOLD);
            }
            g.drawStringShadow(font, p.getDisplayName(), x + 12, ty, 0xFFFFFFFF);
            ty -= 11;
        }
    }

    static boolean someone_here(Minecraft mc) {
        try {
            if (mc.currentWorld == null) return false;
            for (Player p : mc.currentWorld.players) if (p != mc.thePlayer) return true;
        } catch (RuntimeException ignored) { }
        return false;
    }

    static int pulse(int argb) {
        float k = 0.84F + 0.16F * (float) Math.sin(System.currentTimeMillis() * (2 * Math.PI / PULSE_MS));
        int r = Math.min(255, (int) ((argb >> 16 & 255) * k * 1.12F)), g = Math.min(255, (int) ((argb >> 8 & 255) * k * 1.12F)), b = Math.min(255, (int) ((argb & 255) * k * 1.12F));
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    public static void mic(Gui g, int x, int y, int c, boolean off) {
        g.drawRect(x + 3, y, x + 5, y + 1, c);
        g.drawRect(x + 2, y + 1, x + 6, y + 6, c);
        g.drawRect(x + 3, y + 6, x + 5, y + 7, c);
        g.drawRect(x, y + 4, x + 1, y + 7, c);
        g.drawRect(x + 7, y + 4, x + 8, y + 7, c);
        g.drawRect(x + 1, y + 7, x + 2, y + 8, c);
        g.drawRect(x + 6, y + 7, x + 7, y + 8, c);
        g.drawRect(x + 2, y + 8, x + 6, y + 9, c);
        g.drawRect(x + 3, y + 9, x + 5, y + 10, c);
        g.drawRect(x + 1, y + 10, x + 7, y + 11, c);
        if (off) for (int i = 0; i < 11; i++) g.drawRect(x - 1 + i * 10 / 11, y + i, x + i * 10 / 11 + 1, y + i + 1, c);
    }
}
