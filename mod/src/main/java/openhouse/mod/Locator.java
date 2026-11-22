package openhouse.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.render.EntityRendererDispatcher;
import net.minecraft.client.render.FogManager;
import net.minecraft.client.render.camera.EntityCamera;
import net.minecraft.client.render.font.FontRenderer;
import net.minecraft.client.render.renderer.BlendFactor;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.renderer.State;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.player.gamemode.Gamemodes;

public final class Locator {
    private Locator() { }

    static final float DOT_AT = 40;
    static final float SNEAK_MAX = 32;
    static final int DOT = 3;

    static final double PULL_IN = 16;
    static final boolean ON = !"0".equals(System.getProperty("openhouse.locator"));

    static int color_of(Player p) {
        int code = p.chatColor != 0 ? p.chatColor & 15 : NameColor.code_of(p.username);
        return rgb(code);
    }

    public static int color_of(String name) { return rgb(NameColor.code_of(name)); }

    static int rgb(int code) {
        try { return net.minecraft.client.util.helper.Colors.allChatColors[code & 15].value & 0xFFFFFF; }
        catch (Throwable t) { return 0xFFFFFF; }
    }

    public static boolean active(Minecraft mc) {
        return ON && OpenHouseMod.locator_on() && mc != null && mc.currentWorld != null && mc.isMultiplayerWorld() && GameSettings.IMMERSIVE_MODE.drawNames()
                && server_allows(mc);
    }

    public static volatile Boolean forced;

    static boolean server_allows(Minecraft mc) {
        Boolean f = forced;
        if (f != null) return f;
        try { return !Boolean.FALSE.equals(mc.currentWorld.getLevelData().getGameRules().getValue(openhouse.mod.voice.VoiceWire.LOCATOR)); }
        catch (Throwable t) { return true; }
    }

    static void draw_tags(Minecraft mc, float pt) {
        if (!active(mc) || mc.thePlayer == null || mc.activeCamera == null) return;
        Player me = mc.thePlayer;
        Player cam_mob = mc.activeCamera instanceof EntityCamera && ((EntityCamera) mc.activeCamera).mob instanceof Player ? (Player) ((EntityCamera) mc.activeCamera).mob : null;
        boolean i_spectate = me.getGamemode() == Gamemodes.SPECTATOR;
        EntityRendererDispatcher rd = EntityRendererDispatcher.instance;
        FontRenderer font = rd.font;
        if (font == null) return;
        look(mc, rd);
        FogManager.FogState fog = GLRenderer.getFogState();
        FogManager.FogState was = new FogManager.FogState();
        copy(fog, was);
        fog.disable();
        try {
            for (Player p : mc.currentWorld.players) {
                if (p == me || p == cam_mob || p.removed) continue;
                if (p.getGamemode() == Gamemodes.SPECTATOR && !i_spectate) continue;
                double x = p.xo + (p.x - p.xo) * pt - EntityRendererDispatcher.renderPosX;
                double y = p.yo + (p.y - p.yo) * pt + (p.bb.maxY - p.y) + 0.5 - EntityRendererDispatcher.renderPosY;
                double z = p.zo + (p.z - p.zo) * pt - EntityRendererDispatcher.renderPosZ;
                if (p.isPlayerSleeping()) y -= 1.5;
                double d = Math.sqrt(x * x + y * y + z * z);
                if (d < 0.5) continue;
                if (p.isSneaking() && d > SNEAK_MAX) continue;
                tag(rd, font, p, x, y, z, d);
            }
        } catch (Throwable t) {
            if (!said) { said = true; OpenHouseMod.LOG.warn("couldn't draw a player locator tag: {}", t.toString()); }
        } finally {
            copy(was, fog);
            GLRenderer.setDepthMask(true);
            GLRenderer.enableState(State.DEPTH_TEST);
            GLRenderer.disableState(State.BLEND);
            GLRenderer.globalSetLightEnabled(true);
            GLRenderer.setColor4f(1, 1, 1, 1);
        }
    }
    private static boolean said;

    private static final java.nio.FloatBuffer cam = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private static boolean cam_set;

    public static void cam_before_clouds() {

        if (!openhouse.gate.Gate.v7()) return;
        cam.clear();
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, cam);
        cam_set = true;
    }

    public static void draw(Minecraft mc, float pt) {
        if (!cam_set) { draw_tags(mc, pt); return; }
        cam_set = false;
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glLoadMatrixf(cam);
        try { draw_tags(mc, pt); } finally { org.lwjgl.opengl.GL11.glPopMatrix(); }
    }

    static final long STILL_MS = 1000;
    static final double BIG_FROM = 120, BIG_FULL = 250;
    static final float BIG = 1.5F;
    static float looking;
    static double last_yaw, last_pitch, last_x, last_y, last_z;
    static long still_since, last_frame;

    static void look(Minecraft mc, EntityRendererDispatcher rd) {
        long now = System.currentTimeMillis();
        boolean moved = Math.abs(rd.viewLerpYaw - last_yaw) > 0.05 || Math.abs(rd.viewLerpPitch - last_pitch) > 0.05
                || Math.abs(rd.viewLerpPosX - last_x) + Math.abs(rd.viewLerpPosY - last_y) + Math.abs(rd.viewLerpPosZ - last_z) > 0.01;
        if (moved || still_since == 0) still_since = now;
        last_yaw = rd.viewLerpYaw; last_pitch = rd.viewLerpPitch;
        last_x = rd.viewLerpPosX; last_y = rd.viewLerpPosY; last_z = rd.viewLerpPosZ;
        boolean list = mc.currentScreen == null && GameSettings.KEY_PLAYER_LIST.isPressed();
        boolean want = list || now - still_since >= STILL_MS;
        float dt = last_frame == 0 ? 0 : Math.min(0.1F, (now - last_frame) / 1000F);
        last_frame = now;

        looking = want ? Math.min(1, looking + dt / 0.3F) : Math.max(0, looking - dt / 0.15F);
    }

    static float swell(double d) {
        if (looking <= 0 || d <= BIG_FROM) return 1;
        double k = Math.min(1, (d - BIG_FROM) / (BIG_FULL - BIG_FROM));
        float e = looking * looking * (3 - 2 * looking);
        return 1 + (BIG - 1) * (float) k * e;
    }

    static void copy(FogManager.FogState a, FogManager.FogState b) {
        b.fogColor.set(a.fogColor);
        b.fogMode = a.fogMode;
        b.fogStart = a.fogStart;
        b.fogEnd = a.fogEnd;
        b.fogDensity = a.fogDensity;
    }

    static void tag(EntityRendererDispatcher rd, FontRenderer font, Player p, double x, double y, double z, double d) {
        int col = color_of(p);
        boolean sneak = p.isSneaking(), talking = openhouse.mod.voice.Voice.speaking(p.id);

        boolean dot = d > DOT_AT && !sneak;
        float s = dot ? (float) (0.0062 * d) * swell(d) : (float) Math.max(0.026666667 * Math.max(1, Math.sqrt(d) / 2), 0.0047 * d);
        if (d > PULL_IN) { double k = PULL_IN / d; x *= k; y *= k; z *= k; s *= (float) k; }
        GLRenderer.pushFrame();
        GLRenderer.modelM4f().translate((float) x, (float) y, (float) z);
        GLRenderer.modelM4f().rotateY((float) Math.toRadians(-rd.viewLerpYaw));
        GLRenderer.modelM4f().rotateX((float) Math.toRadians(rd.viewLerpPitch));
        GLRenderer.modelM4f().scale(-s, -s, s);
        GLRenderer.globalSetLightEnabled(false);
        GLRenderer.setDepthMask(false);
        if (sneak) GLRenderer.enableState(State.DEPTH_TEST); else GLRenderer.disableState(State.DEPTH_TEST);
        GLRenderer.enableState(State.BLEND);
        GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        TessellatorGeneral t = GLRenderer.getTessellator();
        if (dot) {
            GLRenderer.pushFrame();
            GLRenderer.setShader(Shaders.COLOR);
            t.startDrawingQuads();
            if (talking) rgba(t, 0xF2C14E, 1); else t.setColor4f(0.07F, 0.06F, 0.08F, 0.8F);
            quad(t, -DOT - 1, -DOT - 1, DOT + 1, DOT + 1);
            rgba(t, col, 1);
            quad(t, -DOT, -DOT, DOT, DOT);
            t.draw();
            GLRenderer.popFrame();
            GLRenderer.popFrame();
            return;
        }
        String name = p.getDisplayName();
        if (name == null || name.isEmpty()) name = p.username;
        int half = font.stringWidth(name) / 2;

        float fa = Math.max(0.35F, Afk.alpha(p));
        GLRenderer.pushFrame();
        GLRenderer.setShader(Shaders.COLOR);
        t.startDrawingQuads();
        t.setColor4f(0x12 / 255F, 0x10 / 255F, 0x14 / 255F, 0.6F * fa);
        quad(t, -half - 2, -2, half + 2, 9);
        rgba(t, col, fa);
        quad(t, -half - 2, 9, half + 2, 10);
        if (talking) {

            t.setColor4f(0x12 / 255F, 0x10 / 255F, 0x14 / 255F, 0.6F * fa);
            quad(t, -half - 13, -2, -half - 3, 9);
            rgba(t, col, fa);
            long now = System.currentTimeMillis();
            for (int b = 0; b < 3; b++) {
                int h = 2 + (int) (5 * (0.5 + 0.5 * Math.sin(now / 90.0 + b * 2.1 + p.id)));
                quad(t, -half - 12 + b * 3, 8 - h, -half - 10 + b * 3, 8);
            }
        }
        t.draw();
        GLRenderer.popFrame();
        font.render(name, -half, 0).setColor((int) (255 * fa) << 24 | col).call();
        GLRenderer.popFrame();
    }

    static void rgba(TessellatorGeneral t, int rgb, float a) {
        t.setColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, a);
    }

    static void quad(TessellatorGeneral t, int x1, int y1, int x2, int y2) {
        t.addVertex(x1, y1, 0);
        t.addVertex(x1, y2, 0);
        t.addVertex(x2, y2, 0);
        t.addVertex(x2, y1, 0);
    }
}
