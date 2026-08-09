package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.MachineMetadata;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Pseudo
@Mixin(targets = "appeng.items.misc.ItemEncodedPattern")
public abstract class MixinItemEncodedPatternTooltip {
    @Inject(method = "addCheckedInformation", at = @At("RETURN"), remap = false, require = 1)
    private void ae2QuickEncodingUpload$appendMetadataTooltip(
            ItemStack stack, World world, List<String> lines, ITooltipFlag advancedTooltips,
            CallbackInfo callback) {
        if (stack == null || !stack.hasTagCompound() || lines == null) {
            return;
        }

        NBTTagCompound metadata = stack.getTagCompound().getCompoundTag(MachineMetadata.NBT_KEY);
        if (metadata.hasNoTags()) {
            return;
        }

        appendMachineNames(lines, metadata);
    }

    private static void appendMachineNames(List<String> lines, NBTTagCompound metadata) {
        Set<String> processingAliases = new LinkedHashSet<>();
        collectValues(metadata, processingAliases,
                "CategoryUid", "ProcessingMethod", "processing", "category",
                "Processing", "MethodName", "methodName", "ProcessingMethods",
                "Methods", "methods");

        Set<String> machineNames = new LinkedHashSet<>();
        collectMachineValues(metadata, machineNames, processingAliases,
                "MachineName", "machineName", "Machine", "machine", "MachineNames");
        if (machineNames.isEmpty()) {
            return;
        }

        StringBuilder line = new StringBuilder("\u673a\u5668\u540d\uff1a");
        if (!GuiScreen.isShiftKeyDown()) {
            line.append(machineNames.iterator().next());
            if (machineNames.size() > 1) {
                line.append('\u2026');
            }
        } else {
            int index = 0;
            for (String machineName : machineNames) {
                if (index++ > 0) {
                    line.append('\u3001');
                }
                line.append(machineName);
            }
        }
        lines.add(line.toString());
    }

    private static void collectValues(NBTTagCompound metadata, Set<String> values, String... keys) {
        for (String key : keys) {
            if (metadata.hasKey(key, 8)) {
                addProcessingValue(values, metadata.getString(key));
            } else if (metadata.hasKey(key, 9)) {
                NBTTagList list = metadata.getTagList(key, 8);
                for (int index = 0; index < list.tagCount(); index++) {
                    addProcessingValue(values, list.getStringTagAt(index));
                }
            }
        }
    }

    private static void collectMachineValues(NBTTagCompound metadata, Set<String> values,
                                             Set<String> processingAliases, String... keys) {
        for (String key : keys) {
            if (metadata.hasKey(key, 8)) {
                addMachineValue(values, processingAliases, metadata.getString(key));
            } else if (metadata.hasKey(key, 9)) {
                NBTTagList list = metadata.getTagList(key, 8);
                for (int index = 0; index < list.tagCount(); index++) {
                    addMachineValue(values, processingAliases, list.getStringTagAt(index));
                }
            }
        }
    }

    private static void addProcessingValue(Set<String> values, String rawValue) {
        String value = cleanText(rawValue);
        if (value != null && !value.isEmpty()) {
            values.add(value.toLowerCase(Locale.ROOT));
        }
    }

    private static void addMachineValue(Set<String> values, Set<String> processingAliases,
                                        String rawValue) {
        String value = cleanText(rawValue);
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("Nothing")) {
            return;
        }

        if (I18n.hasKey(value)) {
            String localized = I18n.format(value);
            if (localized == null || localized.equals(value)) {
                return;
            }
            value = cleanText(localized);
            if (value == null || value.isEmpty()) {
                return;
            }
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        if (processingAliases.contains(normalized) || isUnlocalizedIdentifier(value, normalized)) {
            return;
        }
        values.add(value);
    }

    private static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = TextFormatting.getTextWithoutFormattingCodes(value);
        return (cleaned == null ? value : cleaned).trim();
    }

    private static boolean isUnlocalizedIdentifier(String value, String normalized) {
        if (normalized.indexOf(':') >= 0
                || normalized.startsWith("tile.")
                || normalized.startsWith("item.")
                || normalized.startsWith("block.")
                || normalized.startsWith("fluid.")
                || normalized.startsWith("entity.")
                || normalized.startsWith("container.")) {
            return true;
        }
        if (normalized.matches("[a-z0-9_\\-]+\\.[a-z0-9_\\-.]+")) {
            return true;
        }
        if (value.equals(normalized) && normalized.matches("[a-z0-9_\\-]+")) {
            return true;
        }
        return value.matches("[A-Za-z][A-Za-z0-9_]*")
                && (value.endsWith("Block") || value.endsWith("Item")
                || value.endsWith("Fluid") || value.endsWith("Machine"));
    }
}

