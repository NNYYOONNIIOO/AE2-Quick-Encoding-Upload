package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.RecipeCatalystResolver;
import mezz.jei.api.recipe.IRecipeCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Captures the complete catalyst list returned by HEI for a recipe category. */
@Pseudo
@Mixin(targets = "mezz.jei.recipes.RecipeRegistry")
public abstract class MixinJeiRecipeRegistry {
    @Inject(method = "getRecipeCatalysts(Lmezz/jei/api/recipe/IRecipeCategory;)Ljava/util/List;",
            at = @At("RETURN"), remap = false, require = 0)
    private void ae2QuickEncodingUpload$captureRecipeCatalysts(
            IRecipeCategory<?> category,
            CallbackInfoReturnable<List<Object>> callback) {
        if (category != null) {
            RecipeCatalystResolver.captureRecipeCatalyst(
                    callback.getReturnValue(), new String[]{category.getUid()});
        }
    }
}
