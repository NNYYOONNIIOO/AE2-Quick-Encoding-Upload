package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.RecipeCatalystResolver;
import mezz.jei.ingredients.IngredientRegistry;
import mezz.jei.recipes.RecipeRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the same catalysts that HEI renders in the recipe GUI's left list. */
@Pseudo
@Mixin(targets = "mezz.jei.startup.ModRegistry")
public abstract class MixinJeiModRegistry {
    @Inject(method = "addRecipeCatalyst(Ljava/lang/Object;[Ljava/lang/String;)V",
            at = @At("HEAD"), remap = false, require = 0)
    private void ae2QuickEncodingUpload$captureRecipeCatalyst(
            Object catalyst, String[] categoryUids, CallbackInfo callback) {
        RecipeCatalystResolver.captureRecipeCatalyst(catalyst, categoryUids);
    }

    @Inject(method = "createRecipeRegistry(Lmezz/jei/ingredients/IngredientRegistry;)Lmezz/jei/recipes/RecipeRegistry;",
            at = @At("HEAD"), remap = false, require = 0)
    private void ae2QuickEncodingUpload$captureRecipeCatalystTableAtHead(
            IngredientRegistry ingredientRegistry,
            CallbackInfo callback) {
        RecipeCatalystResolver.captureModRegistry(this);
    }

    @Inject(method = "createRecipeRegistry(Lmezz/jei/ingredients/IngredientRegistry;)Lmezz/jei/recipes/RecipeRegistry;",
            at = @At("RETURN"), remap = false, require = 0)
    private void ae2QuickEncodingUpload$captureRecipeCatalystTable(
            IngredientRegistry ingredientRegistry,
            CallbackInfoReturnable<RecipeRegistry> callback) {
        RecipeCatalystResolver.captureModRegistry(this);
    }
}
