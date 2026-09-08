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
    private static volatile Map<Long, ClientDCInternalInv> synchronizedInterfaceInventories =
            Collections.emptyMap();

    /** Receives the complete interface map directly from GuiInterfaceTerminal. */
    public static void captureGuiInterfaceInventories(Object gui) {
        Map<Long, ClientDCInternalInv> captured = new java.util.LinkedHashMap<>();
        if (gui != null) {
            try {
                for (Class<?> type = gui.getClass(); type != null; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (!Map.class.isAssignableFrom(field.getType())) {
                            continue;
                        }
                        field.setAccessible(true);
                        Object value = field.get(gui);
                        if (!(value instanceof Map)) {
                            continue;
                        }
                        for (Object candidate : ((Map<?, ?>) value).values()) {
                            if (candidate instanceof ClientDCInternalInv) {
                                ClientDCInternalInv inventory = (ClientDCInternalInv) candidate;
                                captured.put(inventory.getId(), inventory);
                            }
                        }
                    }
                }
            } catch (ReflectiveOperationException | SecurityException e) {
                com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] failed to capture GUI interface map", e);
            }
        }
        synchronizedInterfaceInventories = captured.isEmpty()
                ? Collections.emptyMap()
                : captured;
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] GUI Mixin captured synchronized interface count={}",
                synchronizedInterfaceInventories.size());
    }

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
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] transferStackInSlot routing hook invoked");
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
        boolean craftingPattern = isCraftingPattern(pattern);
        if (!craftingPattern && (machineData == null || machineData.hasNoTags())) {
            return false;
        }

        InterfaceTarget target;
        if (craftingPattern) {
            target = findCraftingInterfaceTarget(container);
        } else {
            target = findInterfaceTarget(container, machineData, true);
            if (target == null) {
                target = findInterfaceTarget(container, machineData, false);
            }
        }
        if (target == null) {
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] no matching interface target metadata={}", machineData);
            return false;
        }

        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] sending PLACE_SINGLE sourceSlot={} targetId={} visualSlotPresent={}",
                source.slotNumber, target.id, target.slot != null);
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

    /**
     * Routes a crafting pattern without tying the implementation to one
     * particular crafting machine. An interface that already contains a
     * crafting pattern is authoritative; generic crafting/assembly labels
     * are the fallback for an otherwise empty interface.
     */
    private static InterfaceTarget findCraftingInterfaceTarget(Container container) {
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] searching for generic crafting-capable interface");
        List<ClientDCInternalInv> allInterfaces = findGuiInventories();

        for (ClientDCInternalInv clientInventory : allInterfaces) {
            if (isPlaceholderInterface(clientInventory)
                    || !hasFreePatternSlot(clientInventory) || !hasCraftingPattern(clientInventory)) {
                continue;
            }
            long id = clientInventory.getId();
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] selected crafting interface id={} displayName={} reason=stored-crafting-pattern",
                    id, clientInventory.getName());
            return new InterfaceTarget(id, findEmptyDisconnectedSlot(container, id));
        }

        for (ClientDCInternalInv clientInventory : allInterfaces) {
            if (isPlaceholderInterface(clientInventory) || !hasFreePatternSlot(clientInventory)) {
                continue;
            }
            String displayName = clientInventory.getName();
            String unlocalizedName = clientInventory.getUnlocalizedName();
            boolean nameCapability = isCraftingCapabilityName(displayName)
                    || isCraftingCapabilityName(unlocalizedName);
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] crafting interface candidate id={} displayName={} unlocalizedName={} nameCapability={}",
                    clientInventory.getId(), displayName, unlocalizedName, nameCapability);
            if (nameCapability) {
                long id = clientInventory.getId();
                com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] selected crafting interface id={} displayName={} reason=generic-capability-name",
                        id, displayName);
                return new InterfaceTarget(id, findEmptyDisconnectedSlot(container, id));
            }
        }
        return null;
    }

    private static boolean hasCraftingPattern(ClientDCInternalInv clientInventory) {
        if (clientInventory == null) {
            return false;
        }
        for (int slot = 0; slot < clientInventory.getInventory().getSlots(); slot++) {
            ItemStack stored = clientInventory.getInventory().getStackInSlot(slot);
            if (isCraftingPattern(stored)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCraftingCapabilityName(String value) {
        String normalized = normalize(value);
        return normalized.contains("craft")
                || normalized.contains("assembl")
                || normalized.contains("合成")
                || normalized.contains("装配")
                || normalized.contains("制作")
                || normalized.contains("制造");
    }

    private static InterfaceTarget findInterfaceTarget(Container container,
                                                        NBTTagCompound machineData,
                                                        boolean processingFirst) {
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] findInterfaceTarget invoked");
        if (machineData == null || machineData.hasNoTags()) {
            return null;
        }

        String categoryUid = getCategoryUid(machineData);
        if (!categoryUid.isEmpty()) {
            List<ItemStack> catalysts = RecipeCatalystResolver.getCatalysts(categoryUid);
            // Also upgrade older patterns in memory with the exact HEI-left-list
            // machine aliases before the explicit category match/fallback.
            RecipeCatalystResolver.appendMachineAliases(machineData, categoryUid);
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] category={} registered catalyst count={}",
                    categoryUid, catalysts.size());
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] category={} HEI machines={}",
                    categoryUid, RecipeCatalystResolver.describeCatalysts(categoryUid));
            InterfaceTarget categoryTarget = findCategoryCatalystTarget(container, categoryUid, catalysts);
            if (categoryTarget != null) {
                return categoryTarget;
            }
        }

        String[] keys = processingFirst
                ? new String[]{"ProcessingMethod", "processing", "ProcessingMethods"}
                : new String[]{"MachineName", "machine", "MachineNames"};
        List<String> metadata = values(machineData, keys);
        if (metadata.isEmpty()) {
            return null;
        }

        // GuiInterfaceTerminal receives the complete synchronized interface list.
        // container.inventorySlots only contains the currently rendered page, so
        // inspect the GUI byId map instead. AE2 only synchronizes interfaces
        // whose INTERFACE_TERMINAL setting allows them to appear in this terminal.
        List<ClientDCInternalInv> allInterfaces = findGuiInventories();
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] synchronized interface count={}", allInterfaces.size());
        for (ClientDCInternalInv clientInventory : allInterfaces) {
            if (isPlaceholderInterface(clientInventory) || !hasFreePatternSlot(clientInventory)) {
                continue;
            }

            long trackerId = clientInventory.getId();
            String displayName = clientInventory.getName();
            String unlocalizedName = clientInventory.getUnlocalizedName();
            boolean aliasMatch = matchesMetadata(metadata, displayName, processingFirst)
                    || matchesMetadata(metadata, unlocalizedName, processingFirst)
                    || matchesStoredPatternForCategory(clientInventory, machineData);
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] candidate interface id={} displayName={} unlocalizedName={} category={} aliasMatch={} processingFirst={}",
                    trackerId, displayName, unlocalizedName, categoryUid, aliasMatch, processingFirst);
            if (aliasMatch) {
                com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] selected interface id={} displayName={} unlocalizedName={} reason=pattern-alias processingFirst={}",
                        trackerId, displayName, unlocalizedName, processingFirst);
                // The entry may be outside the currently rendered page. The server
                // uses this synchronized id to insert into the first free pattern slot.
                return new InterfaceTarget(trackerId,
                        findEmptyDisconnectedSlot(container, trackerId));
            }
        }
        // Keep the tracker-map path as a compatibility fallback for AE2
        // builds that do not expose the synchronized data under this name.
        for (Map<?, ?> trackers : trackerMaps(container)) {
            for (Map.Entry<?, ?> entry : trackers.entrySet()) {
                Object tracker = entry.getValue();
                String interfaceName = readStringField(tracker, "unlocalizedName", "termName");
                if ("nothing".equals(normalize(interfaceName))) {
                    continue;
                }
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

    private static InterfaceTarget findCategoryCatalystTarget(Container container,
                                                               String categoryUid,
                                                               List<ItemStack> catalysts) {
        if (catalysts == null || catalysts.isEmpty()) {
            return null;
        }
        for (ClientDCInternalInv clientInventory : findGuiInventories()) {
            if (isPlaceholderInterface(clientInventory) || !hasFreePatternSlot(clientInventory)) {
                continue;
            }
            boolean matches = RecipeCatalystResolver.matchesAny(catalysts, clientInventory);
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] category candidate id={} displayName={} unlocalizedName={} category={} catalystMatch={}",
                    clientInventory.getId(), clientInventory.getName(),
                    clientInventory.getUnlocalizedName(), categoryUid, matches);
            if (matches) {
                long id = clientInventory.getId();
                com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] selected interface id={} displayName={} reason=category-catalyst category={}",
                        id, clientInventory.getName(), categoryUid);
                return new InterfaceTarget(id, findEmptyDisconnectedSlot(container, id));
            }
        }
        return null;
    }

    private static boolean isPlaceholderInterface(ClientDCInternalInv inventory) {
        if (inventory == null) {
            return true;
        }
        return "nothing".equals(normalize(inventory.getName()))
                && "nothing".equals(normalize(inventory.getUnlocalizedName()));
    }

    private static boolean matchesStoredPatternForCategory(ClientDCInternalInv clientInventory,
                                                            NBTTagCompound machineData) {
        String expectedCategory = getCategoryUid(machineData);
        if (expectedCategory.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < clientInventory.getInventory().getSlots(); slot++) {
            ItemStack stored = clientInventory.getInventory().getStackInSlot(slot);
            NBTTagCompound storedData = getMachineData(stored);
            if (storedData == null || storedData.hasNoTags()) {
                continue;
            }
            String storedCategory = getCategoryUid(storedData);
            if (!storedCategory.isEmpty() && expectedCategory.equalsIgnoreCase(storedCategory)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The terminal GUI owns the synchronized ClientDCInternalInv objects.
     * Some AE2 builds leave the objects attached to container slots with the
     * placeholder name "Nothing", so resolve the same id through the GUI
     * map before comparing names.
     */
    private static List<ClientDCInternalInv> findGuiInventories() {
        Map<Long, ClientDCInternalInv> directGuiInventories = synchronizedInterfaceInventories;
        if (directGuiInventories != null && !directGuiInventories.isEmpty()) {
            List<ClientDCInternalInv> result = new ArrayList<>(directGuiInventories.values());
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] synchronized interface count={} source=gui-mixin",
                    result.size());
            return result;
        }
        List<ClientDCInternalInv> result = new ArrayList<>();
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = minecraftClass.getMethod("getMinecraft").invoke(null);
            Field screenField = minecraftClass.getDeclaredField("currentScreen");
            screenField.setAccessible(true);
            Object screen = screenField.get(minecraft);
            if (screen == null) {
                return result;
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
                        if (candidate instanceof ClientDCInternalInv && !result.contains(candidate)) {
                            result.add((ClientDCInternalInv) candidate);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // The GUI is client-only; retain a safe fallback elsewhere.
        }
        return result;
    }

    private static boolean matchesStoredPattern(ClientDCInternalInv clientInventory,
                                                 NBTTagCompound machineData,
                                                 boolean processingFirst) {
        if (clientInventory == null) {
            return false;
        }
        String[] keys = processingFirst
                ? new String[]{"ProcessingMethod", "processing", "ProcessingMethods"}
                : new String[]{"MachineName", "machine", "MachineNames"};
        List<String> expected = values(machineData, keys);
        if (expected.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < clientInventory.getInventory().getSlots(); slot++) {
            ItemStack stored = clientInventory.getInventory().getStackInSlot(slot);
            NBTTagCompound storedData = getMachineData(stored);
            if (storedData == null) {
                continue;
            }
            List<String> actual = values(storedData, keys);
            if (processingFirst ? intersectsProcessing(expected, actual) : intersects(expected, new LinkedHashSet<>(actual))) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsProcessing(List<String> left, List<String> right) {
        for (String first : left) {
            for (String second : right) {
                String firstFamily = processingFamily(first);
                String secondFamily = processingFamily(second);
                if (!firstFamily.isEmpty() && firstFamily.equals(secondFamily)) {
                    return true;
                }
            }
        }
        return false;
    }
    private static boolean hasFreePatternSlot(ClientDCInternalInv clientInventory) {
        if (clientInventory == null) {
            return false;
        }
        for (int slot = 0; slot < clientInventory.getInventory().getSlots(); slot++) {
            if (clientInventory.getInventory().getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
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
                        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] interface data field found on {} with keys {}", type.getName(), nbt.getKeySet());
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
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] interface data fallback keys {}", fallback.getKeySet());
        }
        return fallback;
    }

    private static long disconnectedSlotId(SlotDisconnected slot) {
        long id = slot.getSlot().getId();
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "[AE2QuickEncodingUpload] empty interface target slot id={}", id);
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
     * Matches a processing operation against a generic tiered-capability name.
     * For example, a localized name such as "终极压缩工厂" and an internal name
     * such as "tile.Compressing.Ultimate.Factory" both describe a tiered
     * compression capability. This deliberately does not contain mod names or
     * machine-specific aliases; exact aliases and stored patterns still win.
     */
    private static boolean isCapabilityVariantName(String value) {
        String normalized = normalize(value);
        return normalized.contains("factory") || normalized.contains("machine")
                || normalized.contains("processor") || normalized.contains("plant")
                || normalized.contains("device") || normalized.contains("equipment")
                || normalized.contains("basic") || normalized.contains("advanced")
                || normalized.contains("elite") || normalized.contains("ultimate")
                || normalized.contains("tier") || normalized.contains("工厂")
                || normalized.contains("机器") || normalized.contains("设备")
                || normalized.contains("装置") || normalized.contains("基础")
                || normalized.contains("高级") || normalized.contains("精英")
                || normalized.contains("终极");
    }

    private static Set<String> capabilityRoots(String value) {
        Set<String> roots = new LinkedHashSet<>();
        if (value == null) {
            return roots;
        }

        String splitValue = value.replaceAll("([a-z])([A-Z])", "$1 $2");
        for (String part : splitValue.split("[^\\p{L}\\p{N}]+")) {
            String normalizedPart = normalize(part);
            if (!normalizedPart.isEmpty() && !isGenericCapabilityWord(normalizedPart)) {
                addCapabilityRoot(roots, normalizedPart);
            }
        }

        String residual = normalize(value);
        String[] genericWords = new String[]{
                "tile", "block", "factory", "machine", "processor", "plant", "device",
                "equipment", "basic", "advanced", "elite", "ultimate", "tier",
                "工厂", "机器", "设备", "装置", "基础", "高级", "精英", "终极"
        };
        for (String genericWord : genericWords) {
            residual = residual.replace(genericWord, "");
        }
        addCapabilityRoot(roots, residual);
        return roots;
    }

    private static boolean isGenericCapabilityWord(String value) {
        return value.equals("tile") || value.equals("block") || value.equals("factory")
                || value.equals("machine") || value.equals("processor") || value.equals("plant")
                || value.equals("device") || value.equals("equipment") || value.equals("basic")
                || value.equals("advanced") || value.equals("elite") || value.equals("ultimate")
                || value.equals("tier") || value.equals("工厂") || value.equals("机器")
                || value.equals("设备") || value.equals("装置") || value.equals("基础")
                || value.equals("高级") || value.equals("精英") || value.equals("终极");
    }

    private static void addCapabilityRoot(Set<String> roots, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String root = stemCapabilityWord(value);
        boolean hasLatin = root.matches(".*[a-z].*");
        if ((hasLatin && root.length() >= 4) || (!hasLatin && root.length() >= 2)) {
            roots.add(root);
        }
    }

    private static String stemCapabilityWord(String value) {
        String[] suffixes = new String[]{"tion", "sion", "ment", "ing", "ers", "er", "or", "ed"};
        for (String suffix : suffixes) {
            if (value.endsWith(suffix) && value.length() > suffix.length() + 3) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
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

    private static String getCategoryUid(NBTTagCompound machineData) {
        if (machineData == null) {
            return "";
        }
        String[] keys = new String[]{"CategoryUid", "category", "categoryUid", "recipeCategory"};
        for (String key : keys) {
            if (!machineData.hasKey(key, 8)) {
                continue;
            }
            String value = machineData.getString(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
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

    private static boolean isCraftingPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTagCompound()) {
            return false;
        }
        NBTTagCompound tag = stack.getTagCompound();
        return tag.hasKey("crafting", 1) && tag.getBoolean("crafting");
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
                        && normalizedFirst.equals(normalizedSecond)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String value) {
        value = stripFormattingCodes(value);
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                // Remove Minecraft formatting codes before removing punctuation.
                // Otherwise "锇压缩机§r" becomes "锇压缩机r" and cannot match
                // the same interface name without the reset code.
                .replaceAll("(?i)§[0-9a-fk-or]", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String stripFormattingCodes(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == 167 && index + 1 < value.length()) {
                index++;
                continue;
            }
            result.append(value.charAt(index));
        }
        return result.toString();
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
