package openhouse.mod.mixin;

import net.minecraft.client.gui.paged.Page;
import net.minecraft.client.gui.paged.ScreenPaged;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ScreenPaged.class, remap = false)
public interface ScreenPagedAccessor {
    @Accessor("selectedPage")
    Page openhouse$getSelectedPage();
}
