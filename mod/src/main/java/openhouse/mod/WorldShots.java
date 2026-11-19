package openhouse.mod;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL41;

public final class WorldShots {
    private WorldShots() { }

    public static final int W = 256, H = 144;
    static final long SHOT_MS = 30_000L;

    private static volatile BufferedImage last;
    private static volatile String last_world;
    private static volatile long last_at;
    private static boolean had_screen;
    private static volatile boolean shrinking;

    public static void frame(Minecraft mc) {
        String world = OpenHouseMod.singlePlayerWorld;

        if (world == null || mc.currentWorld == null || (mc.currentWorld.isClientSide && !LocalServer.hosting())) { had_screen = mc.currentScreen != null; return; }
        boolean screen = mc.currentScreen != null;
        long now = System.currentTimeMillis();
        boolean menu_opened = screen && !had_screen;
        had_screen = screen;
        if (shrinking) return;
        if (!menu_opened && (screen || now - last_at < SHOT_MS)) return;
        last_at = now;
        try { grab(mc, world); } catch (Throwable t) { OpenHouseMod.LOG.debug("world shot failed: {}", t.toString()); }
    }

    private static void grab(Minecraft mc, String world) {

        int fb = 0, w = mc.gameWindow.getWidthPixels(), h = mc.gameWindow.getHeightPixels();
        if (mc.renderer instanceof net.minecraft.client.render.shader.ShadersRenderer) {
            net.minecraft.client.render.shader.framebuffer.FrameBuffer wf = ((openhouse.mod.mixin.ShadersRendererAccessor) mc.renderer).openhouse$world_fb();
            if (wf == null || wf.isDeleted()) return;
            net.minecraft.client.render.shader.framebuffer.FrameBuffer o = wf.getOutputBuffer();
            if (o == null || o.isDeleted()) return;
            fb = o.getId();
            w = o.getSize().x(); h = o.getSize().y();
        }
        if (w < 16 || h < 16) return;
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        int read_fb = GL41.glGetInteger(GL41.GL_READ_FRAMEBUFFER_BINDING);
        GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, fb);
        int read_buf = GL41.glGetInteger(GL41.GL_READ_BUFFER);
        GL41.glReadBuffer(fb == 0 ? GL41.GL_BACK : GL41.GL_COLOR_ATTACHMENT0);
        GL41.glPixelStorei(GL41.GL_PACK_ALIGNMENT, 1);
        GL41.glReadPixels(0, 0, w, h, GL41.GL_RGBA, GL41.GL_UNSIGNED_BYTE, buf);
        GL41.glReadBuffer(read_buf);
        GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, read_fb);
        final int fw = w, fh = h;
        shrinking = true;
        Thread t = new Thread(() -> {
            try {
                BufferedImage img = shrink(buf, fw, fh);
                if (img != null) { last = img; last_world = world; }
                else last_at = System.currentTimeMillis() - SHOT_MS + 2000;
            } catch (Throwable ignored) {
            } finally { shrinking = false; }
        }, "OpenHouse world shot");
        t.setDaemon(true);
        t.start();
    }

    static BufferedImage shrink(ByteBuffer b, int w, int h) {
        int cw = w, ch = w * 9 / 16;
        if (ch > h) { ch = h; cw = h * 16 / 9; }
        int ox = (w - cw) / 2, oy = (h - ch) / 2;
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        long lit = 0;
        for (int y = 0; y < H; y++) {
            int y0 = oy + y * ch / H, y1 = Math.max(y0 + 1, oy + (y + 1) * ch / H);
            for (int x = 0; x < W; x++) {
                int x0 = ox + x * cw / W, x1 = Math.max(x0 + 1, ox + (x + 1) * cw / W);
                long r = 0, g = 0, bl = 0; int n = 0;

                for (int sy = y0; sy < y1; sy += 2) {
                    int row = (h - 1 - sy) * w;
                    for (int sx = x0; sx < x1; sx += 2) {
                        int i = (row + sx) * 4;
                        r += b.get(i) & 255; g += b.get(i + 1) & 255; bl += b.get(i + 2) & 255; n++;
                    }
                }
                if (n == 0) n = 1;
                int c = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
                lit += (r + g + bl) / n;
                out.setRGB(x, y, c);
            }
        }

        return lit < (long) W * H * 3 ? null : out;
    }

    public static boolean write(String world, File f) {
        BufferedImage img = last;
        if (img == null || world == null || !world.equals(last_world)) return false;
        try { return ImageIO.write(img, "png", f); } catch (Exception e) { return false; }
    }
}
