package com.ae2quickencodingupload;

import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class MachineMetadata {
    public static final String NBT_KEY = "ae2_quick_encoding_upload";

    private MachineMetadata() {
    }

    public static NBTTagCompound from(IRecipeCategory<?> category) {
        if (category == null) {
            return null;
        }

        String uid = safeUid(category);
        String title = safeTitle(category);
        String categoryKey = (uid + " " + title).toLowerCase(Locale.ROOT);
        if (isCraftingCategory(categoryKey)) {
            return null;
        }

        Set<String> methods = new LinkedHashSet<>();
        Set<String> machineNames = new LinkedHashSet<>();
        add(methods, title);
        add(methods, uid);
        add(methods, path(uid));

        if (containsAny(categoryKey, "smelt", "烧制", "熔炼")) {
            addKnownMachine(Blocks.FURNACE, machineNames);
            add(methods, "smelting");
            add(methods, "smelt");
            add(methods, "烧制");
            add(methods, "熔炼");
            add(machineNames, "furnace");
            add(machineNames, "熔炉");
        } else if (containsAny(categoryKey, "brew", "酿造")) {
            addKnownMachine(Blocks.BREWING_STAND, machineNames);
            add(methods, "brewing");
            add(methods, "brew");
            add(methods, "酿造");
            add(machineNames, "brewing stand");
            add(machineNames, "酿造台");
        } else if (containsAny(categoryKey, "anvil", "铁砧")) {
            addKnownMachine(Blocks.ANVIL, machineNames);
            add(methods, "anvil");
            add(methods, "铁砧");
            add(machineNames, "anvil");
            add(machineNames, "铁砧");
        }

        // Third-party JEI categories commonly use their machine name as the title.
        add(machineNames, title);
        add(machineNames, uid);
        add(machineNames, path(uid));
        if (methods.isEmpty() && machineNames.isEmpty()) {
            return null;
        }

        String primaryMethod = first(methods, title, uid);
        String primaryMachine = first(machineNames, title, uid);
        NBTTagCompound result = new NBTTagCompound();
        result.setString("ProcessingMethod", primaryMethod);
        result.setString("processing", primaryMethod);
        result.setString("MachineName", primaryMachine);
        result.setString("machine", primaryMachine);
        result.setString("CategoryUid", uid);
        result.setString("category", uid);
        result.setTag("ProcessingMethods", toList(methods));
        result.setTag("MachineNames", toList(machineNames));
        return result;
    }

    private static boolean isCraftingCategory(String value) {
        return value.contains("minecraft.crafting")
                || value.contains("minecraft:crafting")
                || value.contains("crafting")
                || value.contains("合成");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static void addKnownMachine(Block block, Set<String> values) {
        ItemStack stack = new ItemStack(block);
        add(values, stack.getDisplayName());
        ResourceLocation registryName = stack.getItem().getRegistryName();
        if (registryName != null) {
            add(values, registryName.toString());
            add(values, registryName.getResourcePath());
        }
    }

    private static NBTTagList toList(Set<String> values) {
        NBTTagList list = new NBTTagList();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                list.appendTag(new NBTTagString(value));
            }
        }
        return list;
    }

    private static String safeUid(IRecipeCategory<?> category) {
        try {
            return emptyIfNull(category.getUid());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeTitle(IRecipeCategory<?> category) {
        try {
            return emptyIfNull(category.getTitle());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String path(String uid) {
        int separator = uid.indexOf(':');
        return separator < 0 ? uid : uid.substring(separator + 1);
    }

    private static String first(Set<String> values, String fallback, String secondFallback) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return !fallback.trim().isEmpty() ? fallback : secondFallback;
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value);
        }
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
