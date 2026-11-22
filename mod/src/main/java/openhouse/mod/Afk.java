package openhouse.mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.net.handler.PacketHandlerClient;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.net.packet.PacketCustomPayload;
import openhouse.mod.voice.VoiceWire;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class Afk {
    private Afk() { }

    public static final float MIN_ALPHA = 0.1F;

    static final String MODE = System.getProperty("openhouse.afk", "on");

    static final Map<Integer, Long> since = new ConcurrentHashMap<>();
    static volatile int fade_ms = 120000;
    static volatile boolean fade = true;
    static volatile int idle_ms = 60000;

    static long last_input = System.currentTimeMillis();
    static float last_yaw, last_pitch;
    static int last_mx = -1, last_my = -1;
    static Boolean sent;
    static long sent_at;
    static PacketHandlerClient sent_on;

    public static void frame(Minecraft mc, PacketHandlerClient l, boolean server_ok) {
        if (l != sent_on) { sent_on = l; sent = null; since.clear(); }
        long now = System.currentTimeMillis();
        boolean act = false;
        try {
            if (Mouse.isCreated() && (Mouse.getX() != last_mx || Mouse.getY() != last_my)) { act = last_mx >= 0; last_mx = Mouse.getX(); last_my = Mouse.getY(); }
            for (int b = 0; b < 3 && !act; b++) if (Mouse.isButtonDown(b)) act = true;
            for (int k = 1; k < 256 && !act; k++) if (Keyboard.isKeyDown(k)) act = true;
        } catch (Throwable ignored) { }
        Player me = mc.thePlayer;
        if (me != null && (me.yRot != last_yaw || me.xRot != last_pitch)) { act = true; last_yaw = me.yRot; last_pitch = me.xRot; }
        if (act) last_input = now;
        if (l == null || !server_ok) return;
        boolean afk = mine(mc, now);

        if (sent == null || sent != afk || now - sent_at > 15000) {
            sent = afk; sent_at = now;
            l.addToSendQueue(new PacketCustomPayload(VoiceWire.CHANNEL, new byte[] { VoiceWire.AFK, (byte) (afk ? 1 : 0) }));
        }
    }

    static boolean mine(Minecraft mc, long now) {
        if (MODE.equals("off")) return false;
        if (MODE.equals("force")) return true;
        try { if (mc.gameWindow != null && !mc.gameWindow.isFocused()) return true; } catch (Throwable ignored) { }
        if (mc.currentScreen != null && mc.currentScreen.isPauseScreen()) return true;
        return idle_ms > 0 && now - last_input > idle_ms;
    }

    public static void got(byte[] d) {
        if (d.length < 8) return;
        fade = (d[1] & 1) != 0;
        fade_ms = ((d[2] & 0xFF) << 8 | d[3] & 0xFF) * 1000;
        idle_ms = ((d[4] & 0xFF) << 8 | d[5] & 0xFF) * 1000;
        int n = (d[6] & 0xFF) << 8 | d[7] & 0xFF, o = 8;
        long now = System.currentTimeMillis();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < n && o + 8 <= d.length; i++, o += 8) {
            int id = get(d, o), ms = get(d, o + 4);
            seen.add(id);
            long s = now - ms;
            Long had = since.get(id);

            if (had == null || Math.abs(had - s) > 1500) since.put(id, s);
        }
        since.keySet().retainAll(seen);
    }

    static int get(byte[] d, int o) { return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16 | (d[o + 2] & 0xFF) << 8 | d[o + 3] & 0xFF; }

    public static boolean is_afk(int id) { return since.containsKey(id); }

    public static float alpha(Entity e) {
        if (!(e instanceof Player) || !fade || since.isEmpty()) return 1;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && e == mc.thePlayer) return 1;
        Long s = since.get(e.id);
        if (s == null) return 1;

        float k = (System.currentTimeMillis() - s) / (float) (fade_ms > 0 ? fade_ms : 120000);
        k = Math.max(0, Math.min(1, k));
        return 1 - (1 - MIN_ALPHA) * k;
    }
}
