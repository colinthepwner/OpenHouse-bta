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
    private static final int OPEN = 7701, CODE = 7702;

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
            s.buttons.add(new ButtonElement(CODE, x, y, 200, 20, codeLabel(false)));
            for (ButtonElement b : s.buttons) if (b.id == 1) b.displayString = t.translateKey("gui.openhouse.lan.button.close");
        }
        openhouse$layout();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void openhouse$render(int mx, int my, float partialTick, CallbackInfo ci) {
        openhouse$layout();
    }

    private void openhouse$layout() {
        Screen s = (Screen) (Object) this;
        ButtonElement ours = null;
        for (ButtonElement b : s.buttons) if (b.id == OPEN || b.id == CODE) ours = b;
        if (ours == null || ours.width != 200) return;
        for (ButtonElement b : s.buttons) {
            if (b == ours || !b.visible) continue;
            boolean overlap = b.xPosition < ours.xPosition + ours.width && b.xPosition + b.width > ours.xPosition
                    && b.yPosition < ours.yPosition + ours.height && b.yPosition + b.height > ours.yPosition;
            if (!overlap) continue;
            b.width = 98;
            b.xPosition = s.width / 2 - 100;
            ours.width = 98;
            ours.xPosition = s.width / 2 + 2;
            if (ours.id == CODE) ours.displayString = codeLabel(true);
            return;
        }
    }

    private static String codeLabel(boolean half) {
        I18n t = I18n.getInstance();
        LocalServer ls = LocalServer.current();
        String code = ls == null ? null : ls.code;
        if (code == null) return t.translateKey(half ? "gui.openhouse.lan.button.nocode.short" : "gui.openhouse.lan.button.nocode");
        return t.translateKeyAndFormat(half ? "gui.openhouse.lan.button.code.short" : "gui.openhouse.lan.button.code", code);
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void openhouse$clicked(ButtonElement button, CallbackInfo ci) {
        Minecraft mc = ((Screen) (Object) this).mc;
        if (button.id == OPEN) {
            ci.cancel();
            String world = OpenHouseMod.worldFolder(mc);
            if (world == null) {
                mc.hudIngame.addChatMessage(I18n.getInstance().translateKey("gui.openhouse.lan.noworld"));
                return;
            }

            mc.statsCounter.add(StatList.leaveGameStat, 1);
            mc.changeWorld(null);
            mc.displayScreen(new ScreenOpenToFriends(LocalServer.start(mc, world), false));
        } else if (button.id == CODE) {
            ci.cancel();
            LocalServer ls = LocalServer.current();
            if (ls != null && ls.code != null) {
                try { Global.accessor.copyToClipboard(ls.code); } catch (Exception ignored) { }
                button.displayString = I18n.getInstance().translateKey("gui.openhouse.lan.copied");
            }
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
