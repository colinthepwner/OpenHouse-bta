package openhouse.mod.mixin;

import net.minecraft.core.data.DataLoader;
import net.minecraft.core.data.registry.recipe.RecipeEntryBase;
import net.minecraft.core.data.registry.recipe.RecipeRegistry;
import openhouse.mod.ServerRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DataLoader.class, remap = false)
public abstract class DataLoaderMixin {
    @Redirect(method = "loadRecipeFromServer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/data/registry/recipe/RecipeRegistry;addCustomRecipe(Ljava/lang/String;Lnet/minecraft/core/data/registry/recipe/RecipeEntryBase;)V"),
            require = 0)
    private static void openhouse$serverRecipe(RecipeRegistry registry, String key, RecipeEntryBase<?, ?, ?> recipe) {
        ServerRecipes.add(registry, key, recipe);
    }
}
