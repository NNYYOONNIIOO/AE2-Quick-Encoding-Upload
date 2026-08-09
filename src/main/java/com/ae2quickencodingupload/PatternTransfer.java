package com.ae2quickencodingupload;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.container.slot.SlotFake;
import appeng.container.slot.SlotRestrictedInput;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PatternTransfer {
    private PatternTransfer() {
    }

    public static ItemStack tryTransfer(Container container, EntityPlayer player, int slotIndex) {
        if (container == null || player == null || player.world.isRemote
                || !isInterfaceContainer(container)
                || slotIndex < 0 || slotIndex >= container.inventorySlots.size()) {
            return ItemStack.EMPTY;
        }

        Slot source = container.inventorySlots.get(slotIndex);
        if (source == null || source.inventory != player.inventory) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = source.getStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof ICraftingPatternItem)) {
            return ItemStack.EMPTY;
        }

        NBTTagCompound machineData = getMachineData(stack);
        if (machineData == null || machineData.hasNoTags()) {
            return ItemStack.EMPTY;
        }

        Slot target = findTarget(container, machineData, stack, true);
        if (target == null) {
            target = findTarget(container, machineData, stack, false);
        }
        if (target == null) {
            return ItemStack.EMPTY;
        }

        ItemStack moved = stack.copy();
        moved.setCount(1);
        target.putStack(moved);
        target.onSlotChanged();
        source.decrStackSize(1);
        source.onSlotChanged();
        return moved;
    }

    private static Slot findTarget(Container container, NBTTagCompound machineData,
                                   ItemStack pattern, boolean processingFirst) {
        String[] keys = processingFirst
                ? new String[]{"ProcessingMethod", "processing", "ProcessingMethods"}
                : new String[]{"MachineName", "machine", "MachineNames"};
        List<String> metadata = values(machineData, keys);
        if (metadata.isEmpty()) {
            return null;
        }

        List<SlotFake> configSlots = new ArrayList<>();
        for (Slot slot : container.inventorySlots) {
            if (slot instanceof SlotFake && !slot.getStack().isEmpty()) {
                configSlots.add((SlotFake) slot);
            }
        }
        for (SlotFake configSlot : configSlots) {
            ItemStack configuredMachine = configSlot.getStack();
            MachineDescriptor descriptor = MachineDescriptor.from(configuredMachine);
            if (!intersects(metadata, processingFirst ? descriptor.processing : descriptor.names)) {
                continue;
            }

            Slot sameColumn = findEmptyPatternSlot(container, pattern, configSlot.xPos);
            if (sameColumn != null) {
                return sameColumn;
            }
        }
        return null;
    }

    private static Slot findEmptyPatternSlot(Container container, ItemStack pattern, int xPos) {
        for (Slot slot : container.inventorySlots) {
            if (!(slot instanceof SlotRestrictedInput) || slot instanceof SlotFake
                    || slot.xPos != xPos || !slot.getStack().isEmpty()
                    || !slot.isItemValid(pattern)) {
                continue;
            }
            return slot;
        }
        return null;
    }

    private static boolean isInterfaceContainer(Container container) {
        String name = container.getClass().getName();
        return name.endsWith(".ContainerInterface")
                || name.endsWith(".ContainerInterfaceTerminal")
                || name.endsWith(".ContainerWirelessInterfaceTerminal");
    }

    private static NBTTagCompound getMachineData(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            return null;
        }
        NBTTagCompound root = stack.getTagCompound();
        NBTTagCompound nested = root.getCompoundTag(MachineMetadata.NBT_KEY);
        if (!nested.hasNoTags()) {
            return nested;
        }
        return root.hasKey("MachineName") || root.hasKey("ProcessingMethod") ? root : null;
    }

    private static List<String> values(NBTTagCompound tag, String[] keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (tag.hasKey(key, 8)) {
                add(result, tag.getString(key));
            } else if (tag.hasKey(key, 9)) {
                NBTTagList list = tag.getTagList(key, 8);
                for (int i = 0; i < list.tagCount(); i++) {
                    add(result, list.getStringTagAt(i));
                }
            }
        }
        return result;
    }

    private static boolean intersects(List<String> left, Set<String> right) {
        for (String first : left) {
            String normalizedFirst = normalize(first);
            if (normalizedFirst.isEmpty()) {
                continue;
            }
            for (String second : right) {
                String normalizedSecond = normalize(second);
                if (!normalizedSecond.isEmpty()
                        && (normalizedFirst.equals(normalizedSecond)
                        || normalizedFirst.contains(normalizedSecond)
                        || normalizedSecond.contains(normalizedFirst))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value);
        }
    }

    private static final class MachineDescriptor {
        private final Set<String> names = new LinkedHashSet<>();
        private final Set<String> processing = new LinkedHashSet<>();

        private static MachineDescriptor from(ItemStack stack) {
            MachineDescriptor result = new MachineDescriptor();
            result.add(stack.getDisplayName(), result.names);
            ResourceLocation registryName = stack.getItem().getRegistryName();
            if (registryName != null) {
                result.add(registryName.toString(), result.names);
                result.add(registryName.getResourcePath(), result.names);
                result.add(registryName.toString(), result.processing);
                result.add(registryName.getResourcePath(), result.processing);
            }

            String key = normalize(stack.getDisplayName() + " "
                    + (registryName == null ? "" : registryName.toString()));
            if (key.contains("furnace") || key.contains("熔炉")) {
                result.add("smelting", result.processing);
                result.add("smelt", result.processing);
                result.add("烧制", result.processing);
                result.add("熔炼", result.processing);
                result.add("furnace", result.processing);
                result.add("熔炉", result.processing);
            }
            if (key.contains("brewing") || key.contains("酿造")) {
                result.add("brewing", result.processing);
                result.add("brew", result.processing);
                result.add("酿造", result.processing);
            }
            if (key.contains("anvil") || key.contains("铁砧")) {
                result.add("anvil", result.processing);
                result.add("铁砧", result.processing);
            }
            return result;
        }

        private void add(String value, Set<String> target) {
            if (value != null && !value.trim().isEmpty()) {
                target.add(value);
            }
        }
    }
}
