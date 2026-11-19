package openhouse.mod.mixin;

import java.util.List;
import net.minecraft.client.gui.options.components.OptionsCategory;
import net.minecraft.client.gui.options.components.OptionsComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = OptionsCategory.class, remap = false)
public interface OptionsCategoryAccessor {
    @Accessor("components")
    List<OptionsComponent> openhouse$components();
}
