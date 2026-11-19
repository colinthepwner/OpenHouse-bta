package openhouse.mod.mixin;

import openhouse.mod.OpenHouseMod;
import openhouse.mod.LocalServer;
import openhouse.mod.ScreenOpenToFriends;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenPause;
import net.minecraft.core.Global;
import net.minecraft.core.achievement.stat.StatList;
import net.minecraft.core.lang.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenPause.class, remap = false, priority = 1100)
public abstract class ScreenPauseMixin {
    private static final int OPEN = 7701, CODE = 7702, COPY = 7703;

    private static final int VOICE = 7704;

    private static final int JOINED = 7705;
    private static final int ICON = 20;
    private static long copiedUntil;

    @Inject(method = "init", at = @At("TAIL"))
    private void openhouse$init(CallbackInfo ci) {
        Screen s = (Screen) (Object) this;
        I18n t = I18n.getInstance();
        int x = s.width / 2 - 100, y = s.height / 4 + 56;
        if (!s.mc.isMultiplayerWorld() && s.mc.currentWorld != null) {
            ButtonElement b = new ButtonElement(OPEN, x, y, 200, 20, t.translateKey("gui.openhouse.lan.button.open"));
            if (!LocalServer.supported()) {

                b.enabled = false;
                b.displayString = t.translateKeyAndFormat("gui.openhouse.lan.button.unsupported", LocalServer.gameVersion());
            }
            s.buttons.add(b);
        } else if (LocalServer.hosting() && s.mc.isMultiplayerWorld()) {
            s.buttons.add(new ButtonElement(CODE, x, y, 200 - ICON - 2, 20, codeLabel(s.mc, 200 - ICON - 2)));
            s.buttons.add(new ButtonElement(COPY, x + 200 - ICON, y, ICON, 20, ""));
            for (ButtonElement b : s.buttons) if (b.id == 1) b.displayString = t.translateKey("gui.openhouse.lan.button.close");
        }
        else if (s.mc.isMultiplayerWorld() && s.mc.currentWorld != null) {
            ButtonElement b = new ButtonElement(JOINED, x, y, 200, 20, joinedLabel(s.mc, 200));
            b.enabled = false;
            s.buttons.add(b);
        }
        if (s.mc.currentWorld != null) s.buttons.add(new ButtonElement(VOICE, x - ICON - 2, y, ICON, 20, ""));
        openhouse$layout();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void openhouse$render(int mx, int my, float partialTick, CallbackInfo ci) {
        openhouse$layout();
        Screen s = (Screen) (Object) this;
        for (ButtonElement b : s.buttons) {
            if (b.id == CODE) b.displayString = codeLabel(s.mc, b.width);
            if (b.id == JOINED) b.displayString = joinedLabel(s.mc, b.width);
            if (b.id == OPEN && b.enabled) b.displayString = openLabel(s.mc, b.width);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void openhouse$icon(int mx, int my, float partialTick, CallbackInfo ci) {
        Screen s = (Screen) (Object) this;
        for (ButtonElement b : s.buttons) {
            if (b.id == VOICE && b.visible) {
                boolean off = !openhouse.mod.voice.VoiceCfg.on || openhouse.mod.voice.VoiceCfg.muted;
                int c = off ? openhouse.mod.voice.VoiceHud.RED : openhouse.mod.voice.Voice.talking() ? openhouse.mod.voice.VoiceHud.GOLD : 0xFFE0E0E0;

                int mx0 = b.xPosition + (b.width - 9) / 2, my0 = b.yPosition + (b.height - 12) / 2;
                openhouse.mod.voice.VoiceHud.mic(s, mx0 + 1, my0 + 1, openhouse.mod.voice.VoiceHud.SHADOW, off);
                openhouse.mod.voice.VoiceHud.mic(s, mx0, my0, c, off);
            }
            if (b.id != COPY || !b.visible) continue;

            if (System.currentTimeMillis() < copiedUntil) openhouse.mod.Icons.copied(s, b.xPosition, b.yPosition, b.width, b.height);
            else openhouse.mod.Icons.clipboard(s, b.xPosition, b.yPosition, b.width, b.height);
        }
    }

    private void openhouse$layout() {
        Screen s = (Screen) (Object) this;
        ButtonElement ours = null, copy = null, mic = null;
        for (ButtonElement b : s.buttons) {
            if (b.id == OPEN || b.id == CODE || b.id == JOINED) ours = b;
            if (b.id == COPY) copy = b;
            if (b.id == VOICE) mic = b;
        }
        if (ours == null) return;
        int cx = s.width / 2;
        int full = copy != null ? 200 - ICON - 2 : 200;
        if (ours.width == full && ours.xPosition == cx - 100) {
            ButtonElement other = null;
            for (ButtonElement b : s.buttons) {
                if (b == ours || b == copy || b == mic || !b.visible) continue;
                if (overlaps(b, ours)) { other = b; break; }
            }
            if (other != null) {

                other.width = 98;
                other.xPosition = cx - 100;
                int x = cx + 2, w = 98;
                if (mic != null) { mic.xPosition = x; mic.yPosition = ours.yPosition; x += ICON + 2; w -= ICON + 2; }
                if (copy != null) { copy.xPosition = cx + 2 + 98 - ICON; w -= ICON + 2; }
                ours.xPosition = x;
                ours.width = w;
                relabel(s, ours);
                return;
            }
        }

        if (mic != null && mic.xPosition < ours.xPosition) {
            for (ButtonElement b : s.buttons) {
                if (b == ours || b == copy || b == mic || !b.visible || !overlaps(b, mic)) continue;
                mic.xPosition = ours.xPosition;
                ours.xPosition += ICON + 2;
                ours.width -= ICON + 2;
                relabel(s, ours);
                return;
            }
        }
    }

    private static boolean overlaps(ButtonElement a, ButtonElement b) {
        return a.xPosition < b.xPosition + b.width && a.xPosition + a.width > b.xPosition
                && a.yPosition < b.yPosition + b.height && a.yPosition + a.height > b.yPosition;
    }

    private static void relabel(Screen s, ButtonElement b) {
        if (b.id == CODE) b.displayString = codeLabel(s.mc, b.width);
        if (b.id == JOINED) b.displayString = joinedLabel(s.mc, b.width);
        if (b.id == OPEN && b.enabled) b.displayString = openLabel(s.mc, b.width);
    }

    private static String openLabel(Minecraft mc, int width) {
        I18n t = I18n.getInstance();
        String full = t.translateKey("gui.openhouse.lan.button.open");
        return mc == null || mc.font == null || mc.font.stringWidth(full) <= width - 8 ? full : t.translateKey("gui.openhouse.lan.button.open.short");
    }

    private static String codeLabel(Minecraft mc, int width) {
        I18n t = I18n.getInstance();
        LocalServer ls = LocalServer.current();
        String code = ls == null ? null : ls.code;
        String[] tries;
        if (code == null && ls != null && ls.roomProblem != null) tries = new String[] { t.translateKey("gui.openhouse.lan.button.blocked"), t.translateKey("gui.openhouse.lan.button.blocked.short") };
        else if (code == null) tries = new String[] { t.translateKey("gui.openhouse.lan.button.nocode"), t.translateKey("gui.openhouse.lan.button.nocode.short") };
        else {

            boolean pub = openhouse.mod.OpenHouseDiscord.shareCode;
            tries = new String[] { t.translateKeyAndFormat(pub ? "gui.openhouse.lan.button.code.public" : "gui.openhouse.lan.button.code", code),
                    t.translateKeyAndFormat(pub ? "gui.openhouse.lan.button.code.short.public" : "gui.openhouse.lan.button.code.short", code), code };
        }

        for (String l : tries) if (mc == null || mc.font == null || mc.font.stringWidth(l) <= width - 8) return l;
        return tries[tries.length - 1];
    }

    private static String joinedLabel(Minecraft mc, int width) {
        I18n t = I18n.getInstance();
        String code = OpenHouseMod.joinedRoom();
        if (code == null || code.isEmpty()) code = openhouse.mod.voice.Voice.server_code();
        if (code == null || code.isEmpty()) return t.translateKey("gui.openhouse.lan.button.open");
        String[] tries = { t.translateKeyAndFormat("gui.openhouse.lan.button.joined", code), t.translateKeyAndFormat("gui.openhouse.lan.button.joined.short", code), code };
        for (String l : tries) if (mc == null || mc.font == null || mc.font.stringWidth(l) <= width - 8) return l;
        return tries[tries.length - 1];
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void openhouse$clicked(ButtonElement button, CallbackInfo ci) {
        Minecraft mc = ((Screen) (Object) this).mc;
        if (button.id == VOICE) {
            ci.cancel();
            mc.displayScreen(new openhouse.mod.voice.ScreenVoice((Screen) (Object) this));
        } else if (button.id == OPEN) {
            ci.cancel();
            String world = OpenHouseMod.worldFolder(mc);
            if (world == null) {
                mc.hudIngame.addChatMessage(I18n.getInstance().translateKey("gui.openhouse.lan.noworld"));
                return;
            }

            mc.statsCounter.add(StatList.leaveGameStat, 1);
            LocalServer ls = LocalServer.start(mc, world);
            try {
                mc.changeWorld(null);
            } finally {

                try { openhouse.mod.RegionFiles.release(); } finally { ls.worldClosed(); }
            }
            mc.displayScreen(new ScreenOpenToFriends(ls, false));
        } else if (button.id == COPY) {
            ci.cancel();
            LocalServer ls = LocalServer.current();
            if (ls != null && ls.code != null) {
                try { Global.accessor.copyToClipboard(ls.code); } catch (Exception ignored) { }
                copiedUntil = System.currentTimeMillis() + 1500;
            }
        } else if (button.id == CODE) {

            ci.cancel();
            openhouse.mod.OpenHouseDiscord.shareCode = !openhouse.mod.OpenHouseDiscord.shareCode;
            mc.hudIngame.addChatMessage(I18n.getInstance().translateKey(openhouse.mod.OpenHouseDiscord.shareCode
                    ? "gui.openhouse.discord.public" : "gui.openhouse.discord.friends"));
        } else if (button.id == 1 && LocalServer.hosting() && mc.isMultiplayerWorld()) {

            ci.cancel();
            mc.statsCounter.add(StatList.leaveGameStat, 1);
            mc.currentWorld.sendQuittingDisconnectingPacket();
            mc.changeWorld(null);
            LocalServer ls = LocalServer.current();
            ls.stop();
            mc.displayScreen(new ScreenOpenToFriends(ls, true));
        }
    }
}
