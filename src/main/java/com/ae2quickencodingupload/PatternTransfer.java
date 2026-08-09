package com.ae2quickencodingupload;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.client.me.SlotDisconnected;
import appeng.container.slot.AppEngSlot;
import appeng.container.slot.SlotFake;
import appeng.container.slot.SlotRestrictedInput;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketInventoryAction;
import appeng.helpers.InventoryAction;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class PatternTransfer {
    private PatternTransfer() {
    }

    /**
     * Routes a client-side Shift-click in AE2's Interface Terminal.  That
     * container overrides transferStackInSlot and normally sends PLACE_SINGLE
     * to the first empty remote slot, so the matching must happen before AE2's
     * original method runs.
     */
    public static boolean tryInterfaceTerminalTransfer(Container container,
                                                        EntityPlayer player,
                                                        int slotIndex) {
        if (container == null || player == null || !player.world.isRemote
                || !isInterfaceTerminalContainer(container)
                || slotIndex < 0 || slotIndex >= container.inventorySlots.size()) {
            return false;
        }

        Slot source = container.inventorySlots.get(slotIndex);
        if (!isPlayerSlot(source, player)) {
            return false;
        }

        ItemStack pattern = source.getStack();
        if (pattern.isEmpty() || !(pattern.getItem() instanceof ICraftingPatternItem)) {
            return false;
        }

        NBTTagCompound machineData = getMachineData(pattern);
        if (machineData == null || machineData.hasNoTags()) {
            return false;
        }

        InterfaceTarget target = findInterfaceTarget(container, machineData, true);
        if (target == null) {
            target = findInterfaceTarget(container, machineData, false);
        }
        if (target == null) {
            return false;
        }

        NetworkHandler.instance().sendToServer(new PacketInventoryAction(
                InventoryAction.PLACE_SINGLE, source.slotNumber, target.id));
        return true;
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
                    || slot.getSlotStackLimit() < 1 || !slot.isItemValid(pattern)) {
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

    private static boolean isInterfaceTerminalContainer(Container container) {
        return container.getClass().getName().endsWith("ContainerInterfaceTerminal");
    }

    private static boolean isPlayerSlot(Slot slot, EntityPlayer player) {
        if (slot == null) {
            return false;
        }
        if (slot.inventory == player.inventory) {
            return true;
        }
        return slot instanceof AppEngSlot && ((AppEngSlot) slot).isPlayerSide();
    }

    private static InterfaceTarget findInterfaceTarget(Container container,
                                                        NBTTagCompound machineData,
                                                        boolean processingFirst) {
        String[] keys = processingFirst
                ? new String[]{"ProcessingMethod", "processing", "ProcessingMethods"}
                : new String[]{"MachineName", "machine", "MachineNames"};
        List<String> metadata = values(machineData, keys);
        if (metadata.isEmpty()) {
            return null;
        }

        for (Map<?, ?> trackers : trackerMaps(container)) {
            for (Map.Entry<?, ?> entry : trackers.entrySet()) {
                Object tracker = entry.getValue();
                String interfaceName = readStringField(tracker, "unlocalizedName", "termName");
                if (interfaceName.isEmpty()
                        || !intersects(metadata, Collections.singleton(interfaceName))) {
                    continue;
                }

                long trackerId = readLong(entry.getKey(), Long.MIN_VALUE);
                if (trackerId == Long.MIN_VALUE) {
                    trackerId = readLongField(tracker, "which", "id");
                }
                if (trackerId == Long.MIN_VALUE) {
                    continue;
                }

                SlotDisconnected emptySlot = findEmptyDisconnectedSlot(container, trackerId);
                if (emptySlot != null) {
                    return new InterfaceTarget(trackerId, emptySlot);
                }
            }
        }
        return null;
    }

    private static List<Map<?, ?>> trackerMaps(Container container) {
        List<Map<?, ?>> result = new ArrayList<>();
        for (Class<?> type = container.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(container);
                    if (value instanceof Map && !result.contains(value)) {
                        result.add((Map<?, ?>) value);
                    }
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // An AE2 implementation variant may hide its tracker map.
                }
            }
        }
        return result;
    }

    private static SlotDisconnected findEmptyDisconnectedSlot(Container container, long trackerId) {
        for (Slot slot : container.inventorySlots) {
            if (!(slot instanceof SlotDisconnected) || slot.getHasStack()) {
                continue;
            }
            Object backing = invokeNoArg(slot, "getSlot");
            long slotId = readLong(invokeNoArg(backing, "getId"), Long.MIN_VALUE);
            if (slotId == trackerId) {
                return (SlotDisconnected) slot;
            }
        }
        return null;
    }

    private static String readStringField(Object object, String... names) {
        for (String name : names) {
            Object value = readField(object, name);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return (String) value;
            }
        }
        return "";
    }

    private static long readLongField(Object object, String... names) {
        for (String name : names) {
            long value = readLong(readField(object, name), Long.MIN_VALUE);
            if (value != Long.MIN_VALUE) {
                return value;
            }
        }
        return Long.MIN_VALUE;
    }

    private static Object readField(Object object, String name) {
        if (object == null) {
            return null;
        }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // Continue through the tracker class hierarchy.
            } catch (IllegalAccessException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static long readLong(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static Object invokeNoArg(Object object, String name) {
        if (object == null) {
            return null;
        }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(object);
            } catch (NoSuchMethodException ignored) {
                // Continue through the backing inventory hierarchy.
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
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

    private static final class InterfaceTarget {
        private final long id;
        private final SlotDisconnected slot;

        private InterfaceTarget(long id, SlotDisconnected slot) {
            this.id = id;
            this.slot = slot;
        }
    }
}
