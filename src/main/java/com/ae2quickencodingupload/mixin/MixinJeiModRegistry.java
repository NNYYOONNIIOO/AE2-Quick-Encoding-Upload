package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.RecipeCatalystResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the same catalysts that HEI renders in the recipe GUI's left list. */
@Pseudo
@Mixin(targets = "mezz.jei.startup.ModRegistry")
public abstract class MixinJeiModRegistry {
    @Inject(method = "addRecipeCatalyst", at = @At("HEAD"), remap = false, require = 0)
    private void ae2QuickEncodingUpload$captureRecipeCatalyst(
            Object catalyst, String[] categoryUids, CallbackInfo callback) {
        RecipeCatalystResolver.captureRecipeCatalyst(catalyst, categoryUids);
    }
}
