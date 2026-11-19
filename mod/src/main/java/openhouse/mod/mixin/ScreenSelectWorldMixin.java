package openhouse.mod.mixin;

import java.io.File;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenSelectWorld;
import net.minecraft.core.world.save.SaveFile;
import openhouse.mod.Icons;
import openhouse.mod.SaveFileGuard;
import openhouse.mod.OpenHouseMod;
import openhouse.mod.ScreenRewind;
import openhouse.mod.WorldGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenSelectWorld.class, remap = false)
public abstract class ScreenSelectWorldMixin {
    @Shadow private int selectedWorld;
    @Shadow private List<SaveFile> saveList;
    @Shadow private ButtonElement buttonRename;
    @Shadow private ButtonElement buttonDelete;

    @Unique private static final int REWIND = 7707;
    @Unique private ButtonElement openhouse$rewind;
    @Unique private int openhouse$checked_for = -2;

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

    @Inject(method = "initButtons", at = @At("TAIL"), require = 0)
    private void openhouse$add_rewind(CallbackInfo ci) {
        Screen s = (Screen) (Object) this;
        int l = s.width / 2 - 154;
        openhouse$rewind = new ButtonElement(REWIND, l + 24, s.height - 28, 20, 20, "");
        openhouse$rewind.enabled = false;
        s.buttons.add(openhouse$rewind);
        if (buttonRename != null) { buttonRename.xPosition = l + 48; buttonRename.width = 49; }
        if (buttonDelete != null) { buttonDelete.xPosition = l + 101; buttonDelete.width = 49; }
        openhouse$checked_for = -2;
    }

    @Unique
    private String openhouse$selected() {
        if (saveList == null || selectedWorld < 0 || selectedWorld >= saveList.size() || saveList.get(selectedWorld) == null) return null;
        return saveList.get(selectedWorld).getFileName();
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void openhouse$rewind_state(int mx, int my, float pt, CallbackInfo ci) {
        if (openhouse$rewind == null || openhouse$checked_for == selectedWorld) return;
        openhouse$checked_for = selectedWorld;
        String dir = openhouse$selected();

        openhouse$rewind.enabled = dir != null && !WorldGuard.timeline(WorldGuard.saveDir(dir)).isEmpty();
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void openhouse$rewind_icon(int mx, int my, float pt, CallbackInfo ci) {
        ButtonElement b = openhouse$rewind;
        if (b == null || !b.visible) return;
        Icons.rewind((Screen) (Object) this, b.xPosition, b.yPosition, b.width, b.height, b.enabled);
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$rewind_click(ButtonElement b, CallbackInfo ci) {
        if (b == null || b.id != REWIND) return;
        ci.cancel();
        String dir = openhouse$selected();
        if (!b.enabled || dir == null) return;
        Screen s = (Screen) (Object) this;
        s.mc.displayScreen(new ScreenRewind(s, dir, saveList.get(selectedWorld).getDisplayName()));
    }
}
