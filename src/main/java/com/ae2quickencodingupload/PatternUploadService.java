package com.ae2quickencodingupload;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.helpers.IInterfaceHost;
import appeng.parts.misc.PartInterface;
import appeng.tile.misc.TileInterface;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-side movement of encoded patterns into matching ME interfaces on the player's AE2 network. */
public final class PatternUploadService {
    private static final Logger LOGGER = LogManager.getLogger("ae2_quick_encoding_upload");

    private PatternUploadService() {
    }

    public static int uploadInventory(EntityPlayerMP player) {
        if (player == null || !hasUploadableInventory(player)) {
            return 0;
        }
        IGrid grid = findGridFromPlayer(player);
        if (grid == null) {
            LOGGER.warn("Pattern upload skipped for {}: no AE2 grid found.", player.getName());
            return 0;
        }
        List<InterfaceTarget> targets = findTargets(grid);
        if (targets.isEmpty()) {
            LOGGER.warn("Pattern upload skipped for {}: no real AE2 interfaces found on {}.",
                    player.getName(), grid.getClass().getName());
            return 0;
        }
        int moved = uploadList(player.inventory.mainInventory, targets);
        moved += uploadList(player.inventory.offHandInventory, targets);
        if (moved > 0) {
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            if (player.openContainer != null) {
                player.openContainer.detectAndSendChanges();
            }
        }
        LOGGER.info("Pattern upload for {}: grid={}, interfaces={}, moved={}",
                player.getName(), grid.getClass().getName(), targets.size(), moved);
        return moved;
    }

    private static boolean hasUploadableInventory(EntityPlayerMP player) {
        for (ItemStack stack : player.inventory.mainInventory) {
            if (isUploadable(stack)) {
                return true;
            }
        }
        for (ItemStack stack : player.inventory.offHandInventory) {
            if (isUploadable(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPatternContainer(Container container) {
        String name = container.getClass().getName();
        return name.contains("PatternTerm") || name.contains("PatternEncoder");
    }

    private static int uploadList(List<ItemStack> inventory, List<InterfaceTarget> targets) {
        int moved = 0;
        for (int index = 0; index < inventory.size(); index++) {
            ItemStack remaining = inventory.get(index);
            if (!isUploadable(remaining)) {
                continue;
            }
            while (!remaining.isEmpty()) {
                InterfaceTarget target = findBestTarget(remaining, targets);
                if (target == null) {
                    break;
                }
                int before = remaining.getCount();
                ItemStack after = insertIntoEmptyPatternSlots(target.patterns, remaining);
                if (after.getCount() >= before) {
                    break;
                }
                moved += before - after.getCount();
                remaining = after;
            }
            inventory.set(index, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        }
        return moved;
    }

    private static ItemStack insertIntoEmptyPatternSlots(IItemHandler patterns, ItemStack source) {
        ItemStack remaining = source.copy();
        for (int slot = 0; slot < patterns.getSlots() && !remaining.isEmpty(); slot++) {
            ItemStack existing;
            try {
                existing = patterns.getStackInSlot(slot);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (existing != null && !existing.isEmpty()) {
                continue;
            }
            try {
                ItemStack next = patterns.insertItem(slot, remaining, false);
                remaining = next == null ? ItemStack.EMPTY : next;
            } catch (RuntimeException ignored) {
                // An interface can disappear while the network is updating.
            }
        }
        return remaining;
    }

    private static boolean isUploadable(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !(stack.getItem() instanceof appeng.api.implementations.ICraftingPatternItem)) {
            return false;
        }
        if (isCraftingPattern(stack)) {
            return true;
        }
        return hasMachineAliases(getMachineData(stack));
    }

    private static boolean hasMachineAliases(NBTTagCompound metadata) {
        if (metadata == null || metadata.hasNoTags()) {
            return false;
        }
        Set<String> aliases = new LinkedHashSet<>();
        addValues(aliases, metadata,
                "MachineName", "machine", "MachineNames",
                "ProcessingMethod", "processing", "ProcessingMethods",
                "Methods", "methods");
        return !aliases.isEmpty();
    }

    private static InterfaceTarget findBestTarget(ItemStack pattern,
                                                   List<InterfaceTarget> targets) {
        InterfaceTarget best = null;
        int bestScore = 0;
        for (InterfaceTarget target : targets) {
            int score = matchScore(pattern, target);
            LOGGER.debug("Pattern target candidate host={}, score={}, labels={}, slots={}",
                    target.hostClassName, score, target.identityLabels,
                    countEmptyPatternSlots(target.patterns));
            if (score > bestScore) {
                best = target;
                bestScore = score;
            }
        }
        if (best != null) {
            LOGGER.info("Selected pattern upload target host={}, score={}, labels={}",
                    best.hostClassName, bestScore, best.identityLabels);
        }
        return best;
    }

    private static int matchScore(ItemStack pattern, InterfaceTarget target) {
        if (!hasEmptyPatternSlot(target.patterns)) {
            return 0;
        }
        if (isCraftingPattern(pattern)) {
            if (target.hasCraftingPattern) {
                return 4000;
            }
            return hasGenericCraftingName(target.identityLabels) ? 3000 : 0;
        }

        NBTTagCompound metadata = getMachineData(pattern);
        if (metadata == null) {
            return 0;
        }

        Set<String> processing = new LinkedHashSet<>();
        addValues(processing, metadata,
                "ProcessingMethod", "processing", "ProcessingMethods", "Methods", "methods");
        Set<String> machines = new LinkedHashSet<>();
        addValues(machines, metadata, "MachineName", "machine", "MachineNames");

        // CategoryUid is deliberately not a standalone match. Categories such as
        // minecraft.smelting are shared by several machines and cannot identify an
        // interface. Processing/machine aliases are compared first, with the
        // processing method taking precedence as in AE2's terminal routing.
        int score = 0;
        score = Math.max(score, overlapScore(processing, target.identityLabels, 1200));
        score = Math.max(score, overlapScore(machines, target.identityLabels, 1000));
        // Existing patterns are aliases supplied by the player. They are useful
        // candidates, but remain below the current interface name so an unrelated
        // interface is never selected merely because its category is shared.
        score = Math.max(score, overlapScore(processing, target.existingProcessing, 800));
        score = Math.max(score, overlapScore(machines, target.existingMachines, 700));
        return score;
    }

    private static int overlapScore(Set<String> values, Set<String> candidates, int score) {
        for (String value : values) {
            if (candidates.contains(value)) {
                return score;
            }
        }
        return 0;
    }

    private static boolean hasEmptyPatternSlot(IItemHandler patterns) {
        return countEmptyPatternSlots(patterns) > 0;
    }

    private static int countEmptyPatternSlots(IItemHandler patterns) {
        int empty = 0;
        for (int slot = 0; slot < patterns.getSlots(); slot++) {
            try {
                ItemStack existing = patterns.getStackInSlot(slot);
                if (existing == null || existing.isEmpty()) {
                    empty++;
                }
            } catch (RuntimeException ignored) {
                // Continue checking the other slots.
            }
        }
        return empty;
    }

    private static boolean hasGenericCraftingName(Set<String> labels) {
        for (String label : labels) {
            if (label.contains("assembler") || label.contains("assembly")
                    || label.contains("crafting") || label.contains("molecular")
                    || label.contains(normalize("合成")) || label.contains(normalize("装配"))) {
                return true;
            }
        }
        return false;
    }

    private static List<InterfaceTarget> findTargets(IGrid grid) {
        List<InterfaceTarget> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        try {
            // Use the same two machine classes as AE2's own interface terminal.
            // The grid returns IGridNodes, not the machine objects themselves.
            collectInterfaceTargets(result, seen, grid.getMachines(TileInterface.class));
            collectInterfaceTargets(result, seen, grid.getMachines(PartInterface.class));
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not enumerate AE2 interface hosts from {}.",
                    grid.getClass().getName(), exception);
        }
        return result;
    }

    private static void collectInterfaceTargets(List<InterfaceTarget> result,
                                                 Set<Object> seen,
                                                 Iterable<IGridNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (IGridNode node : nodes) {
            if (node == null || !node.isActive()) {
                continue;
            }

            Object machine = node.getMachine();
            if (!(machine instanceof IInterfaceHost) || !seen.add(machine)) {
                continue;
            }

            IInterfaceHost host = (IInterfaceHost) machine;
            IItemHandler patterns = null;
            if (machine instanceof TileInterface) {
                patterns = ((TileInterface) machine).getInventoryByName("patterns");
            } else if (machine instanceof PartInterface) {
                patterns = ((PartInterface) machine).getInventoryByName("patterns");
            }

            Object duality = invokeNoArg(host, "getInterfaceDuality");
            if (patterns == null) {
                patterns = asHandler(
                        invokeOneArg(duality, "getInventoryByName", "patterns"));
            }
            if (patterns == null) {
                patterns = asHandler(invokeNoArg(host, "getPatterns"));
            }
            if (patterns == null) {
                patterns = asHandler(invokeNoArg(duality, "getPatterns"));
            }
            if (patterns == null) {
                LOGGER.debug("Skipped AE2 interface host {}: no patterns inventory.",
                        host.getClass().getName());
                continue;
            }
            InterfaceTarget target = new InterfaceTarget(patterns);
            target.hostClassName = host.getClass().getName();
            addObjectLabels(target.identityLabels, host);
            addObjectLabels(target.identityLabels, duality);
            readExistingPatterns(target);
            result.add(target);
            LOGGER.debug("Accepted AE2 interface {} with {} pattern slots and labels {}.",
                    host.getClass().getName(), patterns.getSlots(), target.identityLabels);
        }
    }

    private static void readHandlerLabels(Set<String> labels, IItemHandler handler) {
        if (handler == null) {
            return;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            try {
                addStackLabels(labels, handler.getStackInSlot(slot));
            } catch (RuntimeException ignored) {
                // The interface may be removed during a network update.
            }
        }
    }

    private static void readExistingPatterns(InterfaceTarget target) {
        for (int slot = 0; slot < target.patterns.getSlots(); slot++) {
            ItemStack existing;
            try {
                existing = target.patterns.getStackInSlot(slot);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (existing == null || existing.isEmpty()) {
                continue;
            }
            target.hasCraftingPattern |= isCraftingPattern(existing);
            NBTTagCompound metadata = getMachineData(existing);
            if (metadata == null) {
                continue;
            }
            addValues(target.existingMachines, metadata,
                    "MachineName", "machine", "MachineNames");
            addValues(target.existingProcessing, metadata,
                    "ProcessingMethod", "processing", "ProcessingMethods", "Methods", "methods");
        }
    }

    private static void addObjectLabels(Set<String> labels, Object object) {
        if (object == null) {
            return;
        }
        for (String method : new String[]{
                "getTermName", "getCustomName", "getInterfaceName", "getMachineName",
                "getLabel", "getDisplayName"}) {
            addLabel(labels, invokeNoArg(object, method));
        }
        for (String field : new String[]{
                "customName", "myName", "interfaceName", "machineName", "label"}) {
            addLabel(labels, readField(object, field));
        }
    }

    private static void addStackLabels(Set<String> labels, Object value) {
        if (!(value instanceof ItemStack)) {
            return;
        }
        ItemStack stack = (ItemStack) value;
        if (stack.isEmpty()) {
            return;
        }
        addLabel(labels, stack.getDisplayName());
        addLabel(labels, stack.getItem().getUnlocalizedName(stack));
        if (stack.getItem().getRegistryName() != null) {
            addLabel(labels, stack.getItem().getRegistryName().toString());
            addLabel(labels, stack.getItem().getRegistryName().getResourcePath());
        }
    }

    private static void addLabel(Set<String> labels, Object value) {
        if (value instanceof String) {
            String normalized = normalize((String) value);
            if (!normalized.isEmpty()) {
                labels.add(normalized);
            }
        }
    }

    private static void addValues(Set<String> values, NBTTagCompound tag, String... keys) {
        for (String key : keys) {
            if (tag.hasKey(key, 8)) {
                addLabel(values, tag.getString(key));
            } else if (tag.hasKey(key, 9)) {
                NBTTagList list = tag.getTagList(key, 8);
                for (int i = 0; i < list.tagCount(); i++) {
                    addLabel(values, list.getStringTagAt(i));
                }
            }
        }
    }

    private static String first(NBTTagCompound tag, String... keys) {
        for (String key : keys) {
            if (tag.hasKey(key, 8)) {
                String value = tag.getString(key);
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static NBTTagCompound getMachineData(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTagCompound()) {
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
        return stack != null && !stack.isEmpty() && stack.hasTagCompound()
                && stack.getTagCompound().hasKey("crafting", 1)
                && stack.getTagCompound().getBoolean("crafting");
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 167 && i + 1 < value.length()) {
                i++;
            } else {
                clean.append(c);
            }
        }
        return clean.toString().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static Object findGrid(Object source) {
        return findGridDeep(source,
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), 0);
    }

    private static IGrid findGridFromPlayer(EntityPlayerMP player) {
        // PatternTerm/PatternEncoder containers are AEBaseContainers. Their connected
        // network is owned by the action host, even though no interface-terminal GUI
        // is open. This is the same source AE2 uses for its terminal containers.
        Object actionHost = invokeNoArg(player.openContainer, "getActionHost");
        Object actionableNode = invokeNoArg(actionHost, "getActionableNode");
        IGrid grid = asGrid(invokeNoArg(actionableNode, "getGrid"));
        if (grid != null) {
            return grid;
        }
        grid = asGrid(invokeNoArg(player.openContainer, "getNetwork"));
        if (grid != null) {
            return grid;
        }
        grid = asGrid(invokeNoArg(player.openContainer, "getGrid"));
        if (grid != null) {
            return grid;
        }
        grid = asGrid(findGrid(player.openContainer));
        if (grid != null) {
            return grid;
        }
        return asGrid(findGrid(player));
    }

    private static IGrid asGrid(Object value) {
        return value instanceof IGrid ? (IGrid) value : null;
    }

    private static Object findGridDeep(Object object, Set<Object> seen, int depth) {
        if (object == null || depth > 12 || !seen.add(object)) {
            return null;
        }
        Object grid = findGridFrom(object);
        if (grid != null) {
            return grid;
        }
        if (isGridLike(object)) {
            return object;
        }
        if (object instanceof CharSequence || object instanceof Number
                || object instanceof Class || object.getClass().isEnum()) {
            return null;
        }
        if (object instanceof Map) {
            for (Object value : ((Map<?, ?>) object).values()) {
                Object nested = findGridDeep(value, seen, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) {
                Object nested = findGridDeep(value, seen, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (object.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(object); i++) {
                Object nested = findGridDeep(Array.get(object, i), seen, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        }
        for (Class<?> type = object.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || field.getType().isPrimitive() || field.isSynthetic()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object nested = findGridDeep(field.get(object), seen, depth + 1);
                    if (nested != null) {
                        return nested;
                    }
                } catch (IllegalAccessException | SecurityException ignored) {
                    // Continue through the object graph.
                }
            }
        }
        return null;
    }

    private static boolean isGridLike(Object object) {
        if (object == null) {
            return false;
        }
        for (Method method : object.getClass().getMethods()) {
            if ("getMachines".equals(method.getName())
                    && method.getParameterTypes().length == 1) {
                return true;
            }
        }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if ("getMachines".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object findGridFrom(Object object) {
        if (object == null) {
            return null;
        }
        Object actionHost = invokeNoArg(object, "getActionHost");
        Object actionableNode = invokeNoArg(actionHost, "getActionableNode");
        Object actionableGrid = invokeNoArg(actionableNode, "getGrid");
        if (isGridLike(actionableGrid)) {
            return actionableGrid;
        }
        actionableNode = invokeNoArg(object, "getActionableNode");
        actionableGrid = invokeNoArg(actionableNode, "getGrid");
        if (actionableGrid instanceof IGrid) {
            return actionableGrid;
        }
        // AE2's network-backed containers expose the connected IGrid as getNetwork().
        // This is the normal path for the pattern encoder and does not require an
        // interface-terminal GUI to be open.
        Object grid = invokeNoArg(object, "getNetwork");
        if (isGridLike(grid)) {
            return grid;
        }
        grid = invokeNoArg(object, "getGrid");
        if (isGridLike(grid)) {
            return grid;
        }
        Object node = invokeNoArg(object, "getNetworkNode");
        if (node == null) {
            node = invokeNoArg(object, "getGridNode");
        }
        if (node == null) {
            node = invokeNoArg(object, "getNode");
        }
        grid = invokeNoArg(node, "getGrid");
        if (isGridLike(grid)) {
            return grid;
        }
        Object proxy = invokeNoArg(object, "getProxy");
        grid = invokeNoArg(proxy, "getGrid");
        if (isGridLike(grid)) {
            return grid;
        }
        Object host = invokeNoArg(object, "getHost");
        if (host != null && host != object) {
            return findGridFrom(host);
        }
        return null;
    }

    private static IItemHandler asHandler(Object value) {
        return value instanceof IItemHandler ? (IItemHandler) value : null;
    }

    private static void addObjects(List<Object> result, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map) {
            result.addAll(((Map<?, ?>) value).values());
        } else if (value instanceof Iterable) {
            for (Object object : (Iterable<?>) value) {
                result.add(object);
            }
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(Array.get(value, i));
            }
        }
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
                // Continue through the hierarchy.
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

    private static Object invokeOneArg(Object object, String name, Object argument) {
        if (object == null) {
            return null;
        }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterTypes().length != 1) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(object, argument);
                } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException ignored) {
                    // Try another overload instead of abandoning the interface lookup.
                }
            }
        }
        for (Method method : object.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 1) {
                continue;
            }
            try {
                return method.invoke(object, argument);
            } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException ignored) {
                // Try the next public overload.
            }
        }
        return null;
    }

    private static Object readField(Object object, String name) {
        if (object == null) {
            return null;
        }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            } catch (IllegalAccessException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class InterfaceTarget {
        private final IItemHandler patterns;
        private String hostClassName = "unknown";
        private final Set<String> identityLabels = new LinkedHashSet<>();
        private final Set<String> existingMachines = new LinkedHashSet<>();
        private final Set<String> existingProcessing = new LinkedHashSet<>();
        private boolean hasCraftingPattern;

        private InterfaceTarget(IItemHandler patterns) {
            this.patterns = patterns;
        }
    }
}
