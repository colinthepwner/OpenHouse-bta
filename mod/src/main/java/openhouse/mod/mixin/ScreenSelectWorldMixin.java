package openhouse.mod.mixin;

import java.io.File;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenSelectWorld;
import net.minecraft.core.world.save.SaveFile;
import openhouse.OpenHostList;
import openhouse.mod.Icons;
import openhouse.mod.OpenHostWorlds;
import openhouse.mod.SaveFileGuard;
import openhouse.mod.OpenHouseMod;
import openhouse.mod.ScreenOpenHost;
import openhouse.mod.ScreenRewind;
import openhouse.mod.WorldGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenSelectWorld.class, remap = false)
public abstract class ScreenSelectWorldMixin implements OpenHostWorlds.Rows {
    @Shadow private int selectedWorld;
    @Shadow private List<SaveFile> saveList;
    @Shadow private ButtonElement buttonRename;
    @Shadow private ButtonElement buttonDelete;
    @Shadow private ButtonElement buttonSelect;

    @Unique private static final int REWIND = 7707, MOVE = 7708, SRV_FOLDER = 7709;
    @Unique private ButtonElement openhouse$rewind, openhouse$move, openhouse$srvFolder;
    @Unique private int openhouse$checked_for = -2;
    @Unique private long openhouse$checked_at;
    @Unique private final java.util.Map<SaveFile, OpenHostList.Server> openhouse$oh = new java.util.IdentityHashMap<>();
    @Unique private String openhouse$renameText, openhouse$deleteText, openhouse$selectText;
    @Unique private boolean openhouse$ohUp;
    @Unique private long openhouse$stopAt;

    @Inject(method = "loadSaves", at = @At("HEAD"), require = 0)
    private void openhouse$checkSaveFiles(CallbackInfo ci) {
        try {
            File saves = new File(FabricLoader.getInstance().getGameDir().toFile(), "saves");
            WorldGuard.finishRewinds(saves);
            SaveFileGuard.check(saves, new File(OpenHouseMod.dataDir(), "backups"), s -> OpenHouseMod.LOG.warn(s));
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't check the worlds' save files: {}", t.toString());
        }
        openhouse$checked_for = -2;
    }

    @Inject(method = "loadSaves", at = @At("TAIL"), require = 0)
    private void openhouse$openhostWorlds(CallbackInfo ci) {
        try {
            openhouse$oh.clear();
            if (saveList == null) return;
            List<SaveFile> ours = OpenHostWorlds.entries(((Screen) (Object) this).mc, openhouse$oh);
            if (ours.isEmpty()) return;
            SaveFile was = selectedWorld >= 0 && selectedWorld < saveList.size() ? saveList.get(selectedWorld) : null;
            saveList.addAll(ours);
            java.util.Collections.sort(saveList);
            if (was != null) selectedWorld = saveList.indexOf(was);
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't list the OpenHost worlds: {}", t.toString());
        }
    }

    @Override public boolean openhouse$openhost(int row) {
        return saveList != null && row >= 0 && row < saveList.size() && openhouse$oh.containsKey(saveList.get(row));
    }

    @Unique private OpenHostList.Server openhouse$server() {
        return saveList == null || selectedWorld < 0 || selectedWorld >= saveList.size() ? null : openhouse$oh.get(saveList.get(selectedWorld));
    }

    @Inject(method = "initButtons", at = @At("TAIL"), require = 0)
    private void openhouse$add_rewind(CallbackInfo ci) {
        Screen s = (Screen) (Object) this;

        openhouse$row = null;
        if (buttonRename != null && buttonDelete != null) {
            int x0 = Math.min(buttonRename.xPosition, buttonDelete.xPosition);
            int x1 = Math.max(buttonRename.xPosition + buttonRename.width, buttonDelete.xPosition + buttonDelete.width);
            openhouse$row = new int[] { x0, buttonDelete.yPosition, x1, buttonDelete.height };
        }
        int y = openhouse$row != null ? openhouse$row[1] : s.height - 28, h = openhouse$row != null ? openhouse$row[3] : 20;
        openhouse$rewind = new ButtonElement(REWIND, 0, y, 20, h, "");
        openhouse$rewind.enabled = false;
        s.buttons.add(openhouse$rewind);
        openhouse$move = new ButtonElement(MOVE, 0, y, 20, h, "");
        openhouse$move.enabled = false;
        s.buttons.add(openhouse$move);
        openhouse$srvFolder = new ButtonElement(SRV_FOLDER, 0, y, 20, h, "");
        openhouse$srvFolder.visible = false;
        s.buttons.add(openhouse$srvFolder);

        openhouse$bfolder = null;
        try {
            java.lang.reflect.Field ff = ScreenSelectWorld.class.getDeclaredField("buttonFolder");
            ff.setAccessible(true);
            ButtonElement bf = (ButtonElement) ff.get(s);
            if (bf != null) {
                openhouse$bfolder = bf;
                openhouse$folderX = bf.xPosition;
                final net.minecraft.core.util.helper.Listener<ButtonElement> was = bf.listener;
                bf.listener = b -> {
                    OpenHostList.Server oh = openhouse$server();
                    if (oh != null) OpenHostWorlds.open(oh.folder);
                    else if (was != null) was.listen(b);
                };
            }
        } catch (Throwable ignored) { }
        if (buttonRename != null) openhouse$renameText = buttonRename.displayString;
        if (buttonDelete != null) openhouse$deleteText = buttonDelete.displayString;
        if (buttonSelect != null) openhouse$selectText = buttonSelect.displayString;
        openhouse$layout(null);
        openhouse$checked_for = -2;
    }

    @Unique private int[] openhouse$row;
    @Unique private ButtonElement openhouse$bfolder;
    @Unique private int openhouse$folderX = -1;

    @Unique private void openhouse$layout(OpenHostList.Server oh) {
        Screen s = (Screen) (Object) this;
        boolean on = oh != null;
        boolean up = on && openhouse$ohUp;

        if (openhouse$srvFolder != null) openhouse$srvFolder.visible = on && openhouse$bfolder == null;
        if (buttonRename != null) {
            buttonRename.visible = !on || up;
            if (up) {
                boolean stopping = System.currentTimeMillis() - openhouse$stopAt < 60_000;
                buttonRename.displayString = stopping ? "Stopping..." : "Stop Server";
                buttonRename.enabled = !stopping;
            } else if (!on) buttonRename.displayString = openhouse$renameText;
        }
        if (buttonDelete != null) { buttonDelete.visible = !on; buttonDelete.displayString = openhouse$deleteText; }
        if (buttonSelect != null && openhouse$selectText != null) buttonSelect.displayString = on ? "Join Server" : openhouse$selectText;
        if (on && buttonSelect != null) buttonSelect.enabled = true;
        java.util.List<ButtonElement> icons = new java.util.ArrayList<>(), texts = new java.util.ArrayList<>();
        for (ButtonElement b : new ButtonElement[] { openhouse$rewind, on ? openhouse$move : openhouse$move, on ? openhouse$srvFolder : null }) if (b != null && b.visible) icons.add(b);
        if (!on) { if (openhouse$move != null) openhouse$move.visible = true; }
        for (ButtonElement b : new ButtonElement[] { buttonRename, buttonDelete }) if (b != null && b.visible) texts.add(b);
        openhouse$pack(icons, texts);

        if (buttonRename != null) buttonRename.displayString = openhouse$iconMode.contains(buttonRename) ? "" : openhouse$label(buttonRename);
        if (buttonDelete != null && !on) buttonDelete.displayString = openhouse$iconMode.contains(buttonDelete) ? "" : openhouse$deleteText;
    }

    @Unique private void openhouse$pack(java.util.List<ButtonElement> icons, java.util.List<ButtonElement> texts) {
        Screen s = (Screen) (Object) this;
        int[] row = openhouse$row;
        if (row == null) {
            int l = s.width / 2 - 154;
            row = new int[] { l + 24, s.height - 28, l + 150, 20 };
        }
        int y = row[1], h = row[3], G = 4;
        openhouse$iconMode.clear();
        java.util.List<ButtonElement> all = new java.util.ArrayList<>();
        int x0 = row[0];
        ButtonElement bf = openhouse$bfolder;
        if (bf != null && bf.visible && openhouse$folderX >= 0 && bf.yPosition < y + h && bf.yPosition + bf.height > y) { all.add(bf); x0 = Math.min(x0, openhouse$folderX); }
        all.addAll(icons);
        all.addAll(texts);
        java.util.Set<ButtonElement> mine = new java.util.HashSet<>(all);
        mine.add(openhouse$rewind); mine.add(openhouse$move); mine.add(openhouse$srvFolder); mine.add(buttonRename); mine.add(buttonDelete); mine.add(bf);
        for (ButtonElement o : s.buttons) {
            if (o == null || !o.visible || mine.contains(o)) continue;
            if (o.yPosition >= y + h || o.yPosition + o.height <= y || o.xPosition >= row[2] || o.xPosition + o.width <= x0) continue;
            openhouse$packAround(icons, texts, row, x0);
            return;
        }
        int n = all.size();
        if (n == 0) return;
        int avail = row[2] - x0 - G * (n - 1);
        int[] need = new int[texts.size()];
        int sum = 0;
        for (int i = 0; i < texts.size(); i++) { need[i] = openhouse$need(texts.get(i)); sum += need[i]; }
        int iconCount = n - texts.size();
        boolean words = iconCount * 20 + sum <= avail;
        if (!words) for (ButtonElement t : texts) if (t == buttonDelete || t == buttonRename) openhouse$iconMode.add(t);
        int[] w = new int[n];
        if (words && !texts.isEmpty()) {
            int left = avail - iconCount * 20;
            boolean even = true;
            for (int i = 0; i < n; i++) {
                int t = texts.indexOf(all.get(i));
                w[i] = t < 0 ? 20 : left / texts.size() + (t < left % texts.size() ? 1 : 0);
                if (t >= 0 && w[i] < need[t]) even = false;
            }
            if (!even) {
                int extra = left - sum, done = 0;
                for (int i = 0; i < n; i++) {
                    int t = texts.indexOf(all.get(i));
                    w[i] = t < 0 ? 20 : need[t] + extra / texts.size();
                    done += w[i];
                }
                w[n - 1] += left + iconCount * 20 - done;
            }
        } else {

            int fixed = 0, eq = 0;
            for (int i = 0; i < n; i++) { int t = texts.indexOf(all.get(i)); if (t >= 0 && !openhouse$iconMode.contains(all.get(i))) fixed += need[t]; else eq++; }
            int each = eq == 0 ? 0 : (avail - fixed) / eq, rem = eq == 0 ? 0 : (avail - fixed) % eq;
            for (int i = 0, k = 0; i < n; i++) {
                int t = texts.indexOf(all.get(i));
                if (t >= 0 && !openhouse$iconMode.contains(all.get(i))) w[i] = need[t];
                else { w[i] = each + (k < rem ? 1 : 0); k++; }
            }
        }
        int x = x0;
        for (int i = 0; i < n; i++) {
            ButtonElement e = all.get(i);
            e.xPosition = x; e.yPosition = y; e.width = Math.max(20, w[i]); e.height = h;
            x += e.width + G;
        }
    }

    @Unique private int openhouse$need(ButtonElement b) {
        Screen s = (Screen) (Object) this;
        String label = openhouse$label(b);
        return (s.mc != null && s.mc.font != null ? s.mc.font.stringWidth(label) : label.length() * 6) + 10;
    }

    @Unique private void openhouse$packAround(java.util.List<ButtonElement> icons, java.util.List<ButtonElement> texts, int[] row, int x0) {
        Screen s = (Screen) (Object) this;
        int y = row[1], h = row[3];
        java.util.Set<ButtonElement> mine = new java.util.HashSet<>(icons);
        mine.addAll(texts);
        mine.add(openhouse$rewind); mine.add(openhouse$move); mine.add(openhouse$srvFolder); mine.add(buttonRename); mine.add(buttonDelete); mine.add(openhouse$bfolder);
        java.util.List<int[]> free = new java.util.ArrayList<>();
        int start = openhouse$bfolder != null && openhouse$bfolder.visible ? openhouse$bfolder.xPosition + openhouse$bfolder.width + 4 : x0;
        free.add(new int[] { start, row[2] });
        for (ButtonElement o : s.buttons) {
            if (o == null || !o.visible || mine.contains(o)) continue;
            if (o.yPosition >= y + h || o.yPosition + o.height <= y) continue;
            int a = o.xPosition - 4, b = o.xPosition + o.width + 4;
            java.util.List<int[]> next = new java.util.ArrayList<>();
            for (int[] f : free) {
                if (b <= f[0] || a >= f[1]) { next.add(f); continue; }
                if (a > f[0]) next.add(new int[] { f[0], a });
                if (b < f[1]) next.add(new int[] { b, f[1] });
            }
            free = next;
        }
        java.util.List<ButtonElement> all = new java.util.ArrayList<>(icons);
        for (ButtonElement t : texts) { if (t == buttonDelete || t == buttonRename) openhouse$iconMode.add(t); all.add(t); }
        int seg = 0, x = free.isEmpty() ? start : free.get(0)[0];
        for (ButtonElement e : all) {
            int w = openhouse$iconMode.contains(e) || icons.contains(e) ? 20 : openhouse$need(e);
            while (seg < free.size() && x + w > free.get(seg)[1]) { seg++; if (seg < free.size()) x = free.get(seg)[0]; }
            e.xPosition = x; e.yPosition = y; e.width = w; e.height = h;
            x += w + 4;
        }
    }

    @Unique private final java.util.Set<ButtonElement> openhouse$iconMode = new java.util.HashSet<>();

    @Unique private String openhouse$label(ButtonElement b) {
        if (b == buttonRename && openhouse$server() != null) return System.currentTimeMillis() - openhouse$stopAt < 60_000 ? "Stopping..." : "Stop Server";
        if (b == buttonRename && openhouse$renameText != null) return openhouse$renameText;
        if (b == buttonDelete && openhouse$deleteText != null) return openhouse$deleteText;
        return b.displayString == null ? "" : b.displayString;
    }

    @Unique private static boolean openhouse$over(ButtonElement b, int mx, int my) {
        return mx >= b.xPosition && mx < b.xPosition + b.width && my >= b.yPosition && my < b.yPosition + b.height;
    }

    @Unique private String openhouse$fit(String[] tries, int w) {
        Screen s = (Screen) (Object) this;
        for (String t : tries) if (s.mc == null || s.mc.font == null || s.mc.font.stringWidth(t) <= w - 4) return t;
        return tries[tries.length - 1];
    }

    @Unique
    private String openhouse$selected() {
        if (saveList == null || selectedWorld < 0 || selectedWorld >= saveList.size() || saveList.get(selectedWorld) == null) return null;
        if (openhouse$server() != null) return null;
        return saveList.get(selectedWorld).getFileName();
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void openhouse$rewind_state(int mx, int my, float pt, CallbackInfo ci) {
        OpenHostList.Server oh = openhouse$server();
        openhouse$layout(oh);

        long now = System.currentTimeMillis();
        if (openhouse$rewind == null || openhouse$checked_for == selectedWorld && now - openhouse$checked_at < 1000) return;
        openhouse$checked_for = selectedWorld;
        openhouse$checked_at = now;
        String dir = openhouse$selected();

        boolean was = openhouse$ohUp;
        openhouse$ohUp = oh != null && openhouse.mod.MoveToOpenHost.running(oh, false);
        if (was && !openhouse$ohUp) openhouse$stopAt = 0;
        openhouse$rewind.enabled = oh != null ? !WorldGuard.timeline(oh.world()).isEmpty()
                : dir != null && !WorldGuard.timeline(WorldGuard.saveDir(dir)).isEmpty();
        if (openhouse$move != null) openhouse$move.enabled = dir != null || oh != null;
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void openhouse$rewind_icon(int mx, int my, float pt, CallbackInfo ci) {
        Screen s = (Screen) (Object) this;
        ButtonElement b = openhouse$rewind;
        if (b != null && b.visible) Icons.rewind(s, b.xPosition, b.yPosition, b.width, b.height, b.enabled);
        ButtonElement m = openhouse$move;
        if (m != null && m.visible) {
            OpenHostList.Server oh = openhouse$server();
            if (oh != null) Icons.person(s, m.xPosition, m.yPosition, m.width, m.height, m.enabled);
            else Icons.openhost(s, m.xPosition, m.yPosition, m.width, m.height, m.enabled);
            String tip = oh == null ? "Move to OpenHost: more than 9 players, open while your game is closed"
                    : new File(oh.folder, openhouse.mod.MoveToOpenHost.MOVED).isFile() ? "Move Back: a single player world again" : "Copy Here: a single player copy of this server's world";
            if (mx >= m.xPosition && mx < m.xPosition + m.width && my >= m.yPosition && my < m.yPosition + m.height)
                s.drawStringCenteredShadow(s.fontRenderer, tip, s.width / 2, m.yPosition - 34, 0xA0A0A0);
        }
        boolean stopBtn = openhouse$server() != null && openhouse$ohUp && buttonRename != null && buttonRename.visible;
        if (stopBtn) Icons.red(s, s.fontRenderer, buttonRename);
        if (stopBtn && openhouse$iconMode.contains(buttonRename)) {
            Icons.stopServer(s, buttonRename.xPosition, buttonRename.yPosition, buttonRename.width, buttonRename.height, buttonRename.enabled);
            if (openhouse$over(buttonRename, mx, my)) s.drawStringCenteredShadow(s.fontRenderer, openhouse$label(buttonRename), s.width / 2, buttonRename.yPosition - 34, 0xA0A0A0);
        } else if (buttonRename != null && buttonRename.visible && openhouse$iconMode.contains(buttonRename)) {
            Icons.pencil(s, buttonRename.xPosition, buttonRename.yPosition, buttonRename.width, buttonRename.height, buttonRename.enabled);
            if (openhouse$over(buttonRename, mx, my)) s.drawStringCenteredShadow(s.fontRenderer, openhouse$renameText, s.width / 2, buttonRename.yPosition - 34, 0xA0A0A0);
        }
        if (buttonDelete != null && buttonDelete.visible && openhouse$iconMode.contains(buttonDelete)) {
            Icons.bin(s, buttonDelete.xPosition, buttonDelete.yPosition, buttonDelete.width, buttonDelete.height, buttonDelete.enabled);
            if (openhouse$over(buttonDelete, mx, my)) s.drawStringCenteredShadow(s.fontRenderer, openhouse$deleteText, s.width / 2, buttonDelete.yPosition - 34, 0xA0A0A0);
        }
        if (openhouse$bfolder != null && openhouse$server() != null && openhouse$over(openhouse$bfolder, mx, my))
            s.drawStringCenteredShadow(s.fontRenderer, "Open the server's folder", s.width / 2, openhouse$bfolder.yPosition - 34, 0xA0A0A0);
        ButtonElement f = openhouse$srvFolder;
        if (f != null && f.visible) {
            Icons.folder(s, f.xPosition, f.yPosition, f.width, f.height);
            if (mx >= f.xPosition && mx < f.xPosition + f.width && my >= f.yPosition && my < f.yPosition + f.height)
                s.drawStringCenteredShadow(s.fontRenderer, "Open the server's folder", s.width / 2, f.yPosition - 34, 0xA0A0A0);
        }

    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$rewind_click(ButtonElement b, CallbackInfo ci) {
        if (b == null) return;
        Screen s = (Screen) (Object) this;
        OpenHostList.Server oh = openhouse$server();
        if (oh != null && (b == buttonRename || b == buttonDelete || b == buttonSelect || b.id == MOVE || b.id == SRV_FOLDER)) {
            ci.cancel();
            if (b == buttonSelect) s.mc.displayScreen(ScreenOpenHost.play(s, oh));
            else if (b.id == MOVE) s.mc.displayScreen(ScreenOpenHost.back(s, oh));
            else if (b.id == SRV_FOLDER) OpenHostWorlds.open(oh.folder);
            else if (b == buttonRename && openhouse$ohUp && b.enabled) {
                openhouse.mod.MoveToOpenHost.stop(oh);
                openhouse$stopAt = System.currentTimeMillis();
                openhouse$checked_at = 0;
            }
            return;
        }
        if (b.id == MOVE) {
            ci.cancel();
            String dir = openhouse$selected();
            if (b.enabled && dir != null) s.mc.displayScreen(ScreenOpenHost.move(s, dir, saveList.get(selectedWorld).getDisplayName()));
            return;
        }
        if (b.id != REWIND) return;
        ci.cancel();
        if (oh != null) { if (b.enabled) s.mc.displayScreen(new ScreenRewind(s, oh, saveList.get(selectedWorld).getDisplayName())); return; }
        String dir = openhouse$selected();
        if (!b.enabled || dir == null) return;
        s.mc.displayScreen(new ScreenRewind(s, dir, saveList.get(selectedWorld).getDisplayName()));
    }

    @Inject(method = "selectWorld", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$selectOpenHost(int row, CallbackInfo ci) {
        if (saveList == null || row < 0 || row >= saveList.size()) return;
        OpenHostList.Server oh = openhouse$oh.get(saveList.get(row));
        if (oh == null) return;
        ci.cancel();
        Screen s = (Screen) (Object) this;
        s.mc.displayScreen(ScreenOpenHost.play(s, oh));
    }

    @Inject(method = "tryDeleteWorld", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$noDelete(boolean b, CallbackInfo ci) { if (openhouse$server() != null) ci.cancel(); }

    @Inject(method = "deleteWorld", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$noDelete2(boolean b, int row, CallbackInfo ci) { if (openhouse$openhost(row)) ci.cancel(); }
}
