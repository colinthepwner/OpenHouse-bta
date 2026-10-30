package openhouse.mod;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.core.lang.I18n;
import openhouse.Update;
import openhouse.Version;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

public final class ScreenUpdate extends Screen {
    private enum Phase { OFFER, CHECKING, DOWNLOADING, RESTARTING, FAILED, UP_TO_DATE }

    private final String autojoin;
    private volatile Phase phase;
    private volatile Update.Release release;
    private volatile long done, total;
    private volatile String problem = "";
    private ButtonElement update, later, back;
    private long restartAt = Long.MAX_VALUE;

    public ScreenUpdate(Screen parent, Update.Release release, String autojoin) {
        super(parent);
        this.release = release;
        this.autojoin = autojoin;
        phase = release != null ? Phase.OFFER : Phase.CHECKING;
        if (release == null) work();
    }

    @Override public void init() {
        I18n t = I18n.getInstance();
        buttons.clear();
        update = add(new ButtonElement(1, width / 2 - 100, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.update.button.update")));
        later = add(new ButtonElement(2, width / 2 + 2, height / 4 + 96, 98, 20, t.translateKey("gui.openhouse.update.button.later")));
        back = add(new ButtonElement(0, width / 2 - 100, height / 4 + 120, t.translateKey("gui.openhouse.button.back")));
        layout();
    }

    private void layout() {
        update.visible = update.enabled = phase == Phase.OFFER || phase == Phase.FAILED;
        later.visible = later.enabled = phase == Phase.OFFER;
        back.visible = back.enabled = phase == Phase.FAILED || phase == Phase.UP_TO_DATE || phase == Phase.CHECKING || phase == Phase.DOWNLOADING;
    }

    private void work() {
        Thread t = new Thread(() -> {
            try {
                Update.Release r = release;
                if (r == null || r.version == null) {
                    phase = Phase.CHECKING;
                    r = Updater.check();
                    release = r;
                }
                if (!r.newer()) { phase = Phase.UP_TO_DATE; return; }
                phase = Phase.DOWNLOADING;
                File jar = Updater.download(r, (d, n) -> { done = d; total = n; });
                if (phase != Phase.DOWNLOADING) return;
                Updater.install(jar, autojoin);
                phase = Phase.RESTARTING;
            } catch (Exception e) {
                problem = e.getMessage() == null ? e.toString() : e.getMessage();
                phase = Phase.FAILED;
            }
        }, "openhouse update");
        t.setDaemon(true);
        t.start();
    }

    @Override public void tick() {
        if (phase == Phase.RESTARTING && restartAt == Long.MAX_VALUE) restartAt = System.currentTimeMillis() + 1500;

        if (System.currentTimeMillis() > restartAt) { restartAt = Long.MAX_VALUE - 1; mc.shutdown(); }
        layout();
    }

    @Override protected void buttonClicked(@NotNull ButtonElement button) {
        if (button == update) {
            work();
        } else if (button == later || button == back) {
            if (phase == Phase.DOWNLOADING) phase = Phase.FAILED;
            mc.displayScreen(getParentScreen());
        }
    }

    @Override public void keyPressed(char c, int key, int mx, int my) {
        if (key == Keyboard.KEY_ESCAPE && phase != Phase.RESTARTING) mc.displayScreen(getParentScreen());
    }

    @Override public void render(int mx, int my, float partialTick) {
        renderBackground();
        I18n t = I18n.getInstance();
        int cx = width / 2, y = height / 4 - 10;
        drawStringCenteredShadow(fontRenderer, t.translateKey("gui.openhouse.update.title"), cx, y, 0xFFFFFF);
        List<String> lines = new ArrayList<>();
        int color = 0xA0A0A0;
        Update.Release r = release;
        switch (phase) {
            case OFFER:
                lines.add(t.translateKeyAndFormat("gui.openhouse.update.available", r.version, Version.VERSION));
                lines.add(t.translateKey("gui.openhouse.update.available2"));
                color = 0xFFFFFF;
                break;
            case CHECKING: lines.add(t.translateKey("gui.openhouse.update.checking")); break;
            case DOWNLOADING:
                lines.add(t.translateKeyAndFormat("gui.openhouse.update.downloading", r == null ? "" : r.version,
                        total > 0 ? (int) (done * 100 / total) : 0));
                break;
            case RESTARTING: lines.add(t.translateKey("gui.openhouse.update.restarting")); break;
            case UP_TO_DATE:
                lines.add(t.translateKeyAndFormat("gui.openhouse.update.uptodate", Version.VERSION));
                break;
            case FAILED:
                lines.add(t.translateKey("gui.openhouse.update.failed"));
                lines.add(problem);
                color = 0xFF6060;
                break;
            default:
        }
        int ly = y + 22;
        for (String l : lines) {
            drawStringCenteredShadow(fontRenderer, l, cx, ly, color);
            ly += 11;
        }
        super.render(mx, my, partialTick);
    }
}
