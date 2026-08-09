package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.MachineMetadata;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Pseudo
@Mixin(targets = "appeng.items.misc.ItemEncodedPattern")
public abstract class MixinItemEncodedPatternTooltip {
    @Inject(method = "addCheckedInformation", at = @At("RETURN"), remap = false, require = 1)
    private void ae2QuickEncodingUpload$appendMetadataTooltip(
            ItemStack stack, World world, List<String> lines, ITooltipFlag advancedTooltips,
            CallbackInfo callback) {
        if (stack == null || !stack.hasTagCompound()) {
            return;
        }

        NBTTagCompound metadata = stack.getTagCompound().getCompoundTag(MachineMetadata.NBT_KEY);
        if (metadata.hasNoTags()) {
            return;
        }

        appendValues(lines, metadata, "配方名：",
                "RecipeName", "recipeName", "Recipe", "recipe",
                "CategoryUid", "CategoryName", "categoryName", "Category", "category");
        appendValues(lines, metadata, "机器名：",
                "MachineName", "machineName", "Machine", "machine", "MachineNames");
        appendValues(lines, metadata, "方法名：",
                "ProcessingMethod", "processingMethod", "MethodName", "methodName",
                "Processing", "processing", "ProcessingMethods", "Methods", "methods");
    }

    private static void appendValues(List<String> lines, NBTTagCompound metadata,
                                     String label, String... keys) {
        Set<String> values = new LinkedHashSet<>();
        for (String key : keys) {
            if (metadata.hasKey(key, 8)) {
                addValue(values, metadata.getString(key));
            } else if (metadata.hasKey(key, 9)) {
                NBTTagList list = metadata.getTagList(key, 8);
                for (int index = 0; index < list.tagCount(); index++) {
                    addValue(values, list.getStringTagAt(index));
                }
            }
        }

        if (!values.isEmpty()) {
            lines.add(label + join(values));
        }
    }

    private static void addValue(Set<String> values, String value) {
        if (value != null) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
    }

    private static String join(Iterable<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append("，");
            }
            result.append(value);
        }
        return result.toString();
    }
}
