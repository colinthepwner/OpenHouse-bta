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
            s.buttons.add(new ButtonElement(CODE, x, y, 200 - ICON - 2, 20, codeLabel(false)));
            s.buttons.add(new ButtonElement(COPY, x + 200 - ICON, y, ICON, 20, ""));
            for (ButtonElement b : s.buttons) if (b.id == 1) b.displayString = t.translateKey("gui.openhouse.lan.button.close");
        }
        openhouse$layout();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void openhouse$render(int mx, int my, float partialTick, CallbackInfo ci) {
        openhouse$layout();
        Screen s = (Screen) (Object) this;
        for (ButtonElement b : s.buttons) if (b.id == CODE) b.displayString = codeLabel(b.width < 150);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void openhouse$icon(int mx, int my, float partialTick, CallbackInfo ci) {
        Screen s = (Screen) (Object) this;
        for (ButtonElement b : s.buttons) {
            if (b.id != COPY || !b.visible) continue;
            String tex = System.currentTimeMillis() < copiedUntil ? "/assets/openhouse/gui/copied.png" : "/assets/openhouse/gui/clipboard.png";
            net.minecraft.client.render.TextureManager tm = s.mc.textureManager;
            tm.bindTexture(tm.loadTexture(tex));
            s.drawTexturedModalRect(b.xPosition + (b.width - 12) / 2, b.yPosition + (b.height - 12) / 2, 0, 0, 12, 12, 1.0 / 12, 1.0 / 12);
        }
    }

    private void openhouse$layout() {
        Screen s = (Screen) (Object) this;
        ButtonElement ours = null, copy = null;
        for (ButtonElement b : s.buttons) {
            if (b.id == OPEN || b.id == CODE) ours = b;
            if (b.id == COPY) copy = b;
        }
        int full = copy != null ? 200 - ICON - 2 : 200;
        if (ours == null || ours.width != full) return;
        for (ButtonElement b : s.buttons) {
            if (b == ours || b == copy || !b.visible) continue;
            boolean overlap = b.xPosition < ours.xPosition + ours.width && b.xPosition + b.width > ours.xPosition
                    && b.yPosition < ours.yPosition + ours.height && b.yPosition + b.height > ours.yPosition;
            if (!overlap) continue;
            b.width = 98;
            b.xPosition = s.width / 2 - 100;
            ours.width = copy != null ? 98 - ICON - 2 : 98;
            ours.xPosition = s.width / 2 + 2;
            if (copy != null) copy.xPosition = s.width / 2 + 2 + 98 - ICON;
            if (ours.id == CODE) ours.displayString = codeLabel(true);
            return;
        }
    }

    private static String codeLabel(boolean half) {
        I18n t = I18n.getInstance();
        LocalServer ls = LocalServer.current();
        String code = ls == null ? null : ls.code;
        if (code == null) return t.translateKey(half ? "gui.openhouse.lan.button.nocode.short" : "gui.openhouse.lan.button.nocode");

        boolean pub = openhouse.mod.OpenHouseDiscord.shareCode;
        return t.translateKeyAndFormat(half ? (pub ? "gui.openhouse.lan.button.code.short.public" : "gui.openhouse.lan.button.code.short")
                : (pub ? "gui.openhouse.lan.button.code.public" : "gui.openhouse.lan.button.code"), code);
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
