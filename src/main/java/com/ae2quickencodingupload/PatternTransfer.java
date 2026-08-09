package com.ae2quickencodingupload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.client.me.ClientDCInternalInv;
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
    private static final Logger LOGGER = LogManager.getLogger("ae2_quick_encoding_upload");

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
        LOGGER.info("[AE2QuickEncodingUpload] transferStackInSlot routing hook invoked");
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
        String name = container.getClass().getName();
        return name.endsWith(".ContainerInterfaceTerminal")
                || name.endsWith(".ContainerWirelessInterfaceTerminal");
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
        LOGGER.info("[AE2QuickEncodingUpload] findInterfaceTarget invoked");
        String[] keys = processingFirst
                ? new String[]{"ProcessingMethod", "processing", "ProcessingMethods"}
                : new String[]{"MachineName", "machine", "MachineNames"};
        List<String> metadata = values(machineData, keys);
        if (metadata.isEmpty()) {
            return null;
        }

        // On the client, ContainerInterfaceTerminal.data is not populated by
        // PacketCompressedNBT. AE2 applies that packet to GuiInterfaceTerminal,
        // which creates the ClientDCInternalInv held by each SlotDisconnected.
        // Read the synchronized display and unlocalized names from that object.
        for (Slot slot : container.inventorySlots) {
            if (!(slot instanceof SlotDisconnected) || slot.getHasStack()) {
                continue;
            }

            SlotDisconnected disconnected = (SlotDisconnected) slot;
            ClientDCInternalInv slotInventory = disconnected.getSlot();
            if (slotInventory == null) {
                continue;
            }

            long trackerId = slotInventory.getId();
            ClientDCInternalInv guiInventory = findGuiInventory(trackerId);
            ClientDCInternalInv clientInventory = guiInventory == null ? slotInventory : guiInventory;
            String displayName = clientInventory.getName();
            String unlocalizedName = clientInventory.getUnlocalizedName();
            LOGGER.info("[AE2QuickEncodingUpload] candidate interface id={} displayName={} unlocalizedName={} source={} processingFirst={}",
                    trackerId, displayName, unlocalizedName, guiInventory == null ? "slot" : "gui", processingFirst);
            if (matchesMetadata(metadata, displayName, processingFirst)
                    || matchesMetadata(metadata, unlocalizedName, processingFirst)) {
                LOGGER.info("[AE2QuickEncodingUpload] selected interface id={} displayName={} unlocalizedName={} source={} processingFirst={}",
                        trackerId, displayName, unlocalizedName, guiInventory == null ? "slot" : "gui", processingFirst);
                return new InterfaceTarget(trackerId, disconnected);
            }
        }

        // Keep the tracker-map path as a compatibility fallback for AE2
        // builds that do not expose the synchronized data under this name.
        for (Map<?, ?> trackers : trackerMaps(container)) {
            for (Map.Entry<?, ?> entry : trackers.entrySet()) {
                Object tracker = entry.getValue();
                String interfaceName = readStringField(tracker, "unlocalizedName", "termName");
                if (!matchesMetadata(metadata, interfaceName, processingFirst)) {
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

    /**
     * The terminal GUI owns the synchronized ClientDCInternalInv objects.
     * Some AE2 builds leave the objects attached to container slots with the
     * placeholder name "Nothing", so resolve the same id through the GUI
     * map before comparing names.
     */
    private static ClientDCInternalInv findGuiInventory(long id) {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = minecraftClass.getMethod("getMinecraft").invoke(null);
            Field screenField = minecraftClass.getDeclaredField("currentScreen");
            screenField.setAccessible(true);
            Object screen = screenField.get(minecraft);
            if (screen == null) {
                return null;
            }

            for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (!(value instanceof Map)) {
                        continue;
                    }
                    for (Object candidate : ((Map<?, ?>) value).values()) {
                        if (candidate instanceof ClientDCInternalInv
                                && ((ClientDCInternalInv) candidate).getId() == id) {
                            return (ClientDCInternalInv) candidate;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // The GUI is client-only; keep the common routing class safe elsewhere.
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

    private static NBTTagCompound readInterfaceData(Container container) {
        Object ownerObject = container;
        if (ownerObject == null) {
            return null;
        }

        net.minecraft.nbt.NBTTagCompound fallback = null;
        Class<?> type = ownerObject.getClass();
        while (type != null) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(ownerObject);
                    if (!(value instanceof net.minecraft.nbt.NBTTagCompound)) {
                        continue;
                    }
                    net.minecraft.nbt.NBTTagCompound nbt = (net.minecraft.nbt.NBTTagCompound) value;
                    if ("data".equals(field.getName())) {
                        LOGGER.info("[AE2QuickEncodingUpload] interface data field found on {} with keys {}", type.getName(), nbt.getKeySet());
                        return nbt;
                    }
                    if (fallback == null && (nbt.hasKey("=id") || !nbt.getKeySet().isEmpty())) {
                        fallback = nbt;
                    }
                } catch (Exception ignored) {
                    // Continue scanning the class hierarchy.
                }
            }
            type = type.getSuperclass();
        }
        if (fallback != null) {
            LOGGER.info("[AE2QuickEncodingUpload] interface data fallback keys {}", fallback.getKeySet());
        }
        return fallback;
    }

    private static long disconnectedSlotId(SlotDisconnected slot) {
        long id = slot.getSlot().getId();
        LOGGER.info("[AE2QuickEncodingUpload] empty interface target slot id={}", id);
        return id;
    }

    private static boolean matchesMetadata(List<String> metadata, String candidate,
                                           boolean processingFirst) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return false;
        }
        if (processingFirst) {
            String candidateFamily = processingFamily(candidate);
            if (!candidateFamily.isEmpty()) {
                for (String value : metadata) {
                    if (candidateFamily.equals(processingFamily(value))) {
                        return true;
                    }
                }
            }
        }
        return intersects(metadata, Collections.singleton(candidate));
    }

    /**
     * Processing categories and interface names are different labels for the
     * same machine. Normalize their well-known aliases into a shared family.
     */
    private static String processingFamily(String value) {
        String normalized = normalize(value);
        if (normalized.contains("smelting") || normalized.equals("smelt")
                || normalized.equals("furnace") || normalized.equals("minecraftfurnace")
                || normalized.equals("\u7194\u7089") || normalized.equals("\u70e7\u5236")
                || normalized.equals("\u70e7\u70bc") || normalized.equals("\u7194\u70bc")) {
            return "smelting";
        }
        if (normalized.contains("brewing") || normalized.equals("brew")
                || normalized.equals("\u917f\u9020") || normalized.equals("\u917f\u9020\u53f0")) {
            return "brewing";
        }
        if (normalized.contains("anvil") || normalized.equals("\u94c1\u7827")) {
            return "anvil";
        }
        return "";
    }

    private static SlotDisconnected findEmptyDisconnectedSlot(Container container, long trackerId) {
        for (Slot slot : container.inventorySlots) {
            if (!(slot instanceof SlotDisconnected) || slot.getHasStack()) {
                continue;
            }
            long slotId = disconnectedSlotId((SlotDisconnected) slot);
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
        try {
            Method method = object.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(object);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
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
