package com.ae2quickencodingupload.mixin;

import com.ae2quickencoding.client.RecipeEntry;
import com.ae2quickencoding.model.PatternData;
import com.ae2quickencodingupload.MachineMetadata;
import com.ae2quickencodingupload.PatternMachineDataAccess;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.autocrafting.RecipeBookmarkItem;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.ae2quickencoding.client.RecipeBookmarkCatalog")
public abstract class MixinRecipeBookmarkCatalog {
    @Inject(method = "createEntry", at = @At("RETURN"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$attachMachineData(
            RecipeBookmarkItem<?> bookmark,
            IRecipeWrapper recipe,
            IRecipeCategory<?> category,
            CallbackInfoReturnable<RecipeEntry> callback) {
        RecipeEntry entry = callback.getReturnValue();
        if (entry == null || !entry.isEncodable()) {
            return;
        }
        PatternData data = entry.getPatternData();
        NBTTagCompound machineData = MachineMetadata.from(category);
        Object rawData = data;
        if (rawData instanceof PatternMachineDataAccess && machineData != null && !machineData.hasNoTags()) {
            ((PatternMachineDataAccess) rawData).ae2QuickEncodingUpload$setMachineData(machineData);
        }
    }
}
