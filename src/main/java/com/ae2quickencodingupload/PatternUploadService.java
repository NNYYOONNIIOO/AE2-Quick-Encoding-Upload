package com.ae2quickencodingupload;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
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
        try {
            return uploadInventoryUnsafe(player);
        } catch (RuntimeException | LinkageError exception) {
            // Upload is optional. A discovery failure must never interrupt the
            // encoder that produced the pattern.
            LOGGER.warn("Pattern upload discovery failed; leaving patterns untouched.",
                    exception);
            return 0;
        }
    }

    private static int uploadInventoryUnsafe(EntityPlayerMP player) {
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
        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof appeng.api.implementations.ICraftingPatternItem
                && (isCraftingPattern(stack) || getMachineData(stack) != null);
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
            boolean explicitCraftingTarget = hasCraftingCapability(target.identityLabels);
            if (target.hasCraftingPattern
                    && (!target.hasProcessingPattern || explicitCraftingTarget)) {
                return 4000;
            }
            return explicitCraftingTarget ? 3000 : 0;
        }

        NBTTagCompound metadata = getMachineData(pattern);
        if (metadata == null) {
            return 0;
        }

        String categoryUid = getCategoryUid(metadata);
        if (!categoryUid.isEmpty()) {
            try {
                // Keep the button path in sync with the interface-terminal path:
                // older patterns may only have a category and need the HEI
                // catalyst aliases before their target can be identified.
                RecipeCatalystResolver.appendMachineAliases(metadata, categoryUid);
            } catch (RuntimeException exception) {
                LOGGER.debug("Could not append HEI aliases for category {}.",
                        categoryUid, exception);
            }
        }

        Set<String> processing = new LinkedHashSet<>();
        addValues(processing, metadata,
                "ProcessingMethod", "processing", "ProcessingMethods", "Methods", "methods");
        Set<String> machines = new LinkedHashSet<>();
        addValues(machines, metadata, "MachineName", "machine", "MachineNames");
        Set<String> categories = new LinkedHashSet<>();
        addValues(categories, metadata, "CategoryUid", "category", "Category", "categoryUid");

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
        score = Math.max(score, overlapScore(categories, target.existingCategories, 900));
        score = Math.max(score, catalystMatchScore(categoryUid, target.identityLabels));
        return score;
    }

    private static boolean hasCraftingCapability(Set<String> labels) {
        if (labels == null) {
            return false;
        }
        for (String label : labels) {
            String normalized = normalize(label);
            if (normalized.contains("craft") || normalized.contains("assembl")
                    || normalized.contains("molecular")
                    || normalized.contains("\u5206\u5b50")
                    || normalized.contains("\u5408\u6210")
                    || normalized.contains("\u88c5\u914d")
                    || normalized.contains("\u5236\u4f5c")
                    || normalized.contains("\u5236\u9020")) {
                return true;
            }
        }
        return false;
    }

    private static int catalystMatchScore(String categoryUid, Set<String> targetLabels) {
        if (categoryUid == null || categoryUid.trim().isEmpty() || targetLabels.isEmpty()) {
            return 0;
        }
        List<ItemStack> catalysts;
        try {
            catalysts = RecipeCatalystResolver.getCatalysts(categoryUid);
        } catch (RuntimeException exception) {
            LOGGER.debug("Could not read HEI catalysts for category {}.",
                    categoryUid, exception);
            return 0;
        }
        if (catalysts == null) {
            return 0;
        }
        for (ItemStack catalyst : catalysts) {
            if (catalyst == null || catalyst.isEmpty()) {
                continue;
            }
            Set<String> catalystLabels = new LinkedHashSet<>();
            addLabel(catalystLabels, catalyst.getDisplayName());
            if (catalyst.getItem().getRegistryName() != null) {
                addLabel(catalystLabels, catalyst.getItem().getRegistryName().toString());
                addLabel(catalystLabels, catalyst.getItem().getRegistryName().getResourcePath());
            }
            if (overlapScore(catalystLabels, targetLabels, 850) > 0) {
                return 850;
            }
        }
        return 0;
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
        collectReflectivePatternTargets(result, seen, grid);
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
            addLabel(target.identityLabels, host.getClass().getName());
            if (duality != null) {
                addLabel(target.identityLabels, duality.getClass().getName());
            }
            addObjectLabels(target.identityLabels, host);
            addObjectLabels(target.identityLabels, duality);
            readExistingPatterns(target);
            result.add(target);
            LOGGER.debug("Accepted AE2 interface {} with {} pattern slots and labels {}.",
                    host.getClass().getName(), patterns.getSlots(), target.identityLabels);
        }
    }

    /**
     * Finds optional network machines that expose pattern inventories without
     * implementing AE2's IInterfaceHost. Interface Terminal integrations use
     * the same kind of network entry, so the upload button must not limit itself
     * to the two built-in AE2 interface classes.
     */
    private static void collectReflectivePatternTargets(List<InterfaceTarget> result,
                                                          Set<Object> seenMachines,
                                                          IGrid grid) {
        if (grid == null) {
            return;
        }
        Set<Class<?>> machineTypes = new LinkedHashSet<>();
        machineTypes.add(IGridHost.class);
        collectRegisteredMachineTypes(grid, machineTypes);

        Set<Object> seenHandlers = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        Set<Object> seenNodes = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        int discoveredNodes = 0;
        for (Class<?> machineType : machineTypes) {
            Object nodes = invokeOneArg(grid, "getMachines", machineType);
            discoveredNodes += collectRegisteredGridNodes(result, seenMachines,
                    seenHandlers, nodes, seenNodes);
        }
        LOGGER.debug("Network machine discovery found {} nodes across {} registered machine types.",
                discoveredNodes, machineTypes.size());
        LOGGER.debug("Discovered {} network pattern targets.",
                Math.max(0, result.size()));
    }

    /**
     * AE2 integrations register their own machine class and expose it through
     * the same IGrid#getMachines(Class) API as vanilla AE2 interfaces. Discover
     * those class keys from the grid's machine registry instead of depending on
     * a particular integration's class name.
     */
    private static void collectMachineTypesDeep(Object object, Set<Object> seen,
                                                Set<Class<?>> machineTypes, int depth) {
        if (object == null || depth > 12 || !seen.add(object)
                || machineTypes.size() >= 256) {
            return;
        }
        if (object instanceof Class) {
            Class<?> type = (Class<?>) object;
            if (isMachineTypeCandidate(type)) {
                machineTypes.add(type);
            }
            return;
        }
        if (object instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                collectMachineTypesDeep(entry.getKey(), seen, machineTypes, depth + 1);
                collectMachineTypesDeep(entry.getValue(), seen, machineTypes, depth + 1);
            }
            return;
        }
        if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) {
                collectMachineTypesDeep(value, seen, machineTypes, depth + 1);
            }
            return;
        }
        if (object.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(object); index++) {
                collectMachineTypesDeep(Array.get(object, index), seen, machineTypes, depth + 1);
            }
            return;
        }
        if (object instanceof CharSequence || object instanceof Number
                || object instanceof Class || object.getClass().isEnum()) {
            return;
        }

        for (Class<?> type = object.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || field.getType().isPrimitive() || field.isSynthetic()) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (IllegalAccessException | SecurityException ignored) {
                    continue;
                }
                if (isGridTraversalMember(field.getName(), value)) {
                    collectMachineTypesDeep(value, seen, machineTypes, depth + 1);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0
                        || !isMachineCollectionAccessor(method.getName())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    collectMachineTypesDeep(method.invoke(object), seen, machineTypes, depth + 1);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // An implementation may not expose a readable registry accessor.
                }
            }
        }
    }

    private static boolean isMachineTypeCandidate(Class<?> type) {
        if (type == null || type == Object.class || type.isPrimitive()
                || type.isArray() || type.isEnum()) {
            return false;
        }
        String name = type.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.");
    }

    private static boolean isMachineCollectionAccessor(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("getmachineclasses")
                || normalized.equals("getmachinetypes")
                || normalized.equals("getmachineentries")
                || normalized.equals("getmachinemap")
                || normalized.equals("getmachines")
                || normalized.equals("getgridnodes")
                || normalized.equals("getnodes");
    }

    /**
     * Reads only direct Map fields from the AE2 grid implementation. AE2's
     * machine registry is keyed by the concrete IGridHost class, which is the
     * same registry used by Interface Terminal integrations. This avoids
     * traversing live machine fields and therefore cannot resolve unrelated
     * optional classes such as GTCEEnergyAdapter.
     */
    private static void collectRegisteredMachineTypes(IGrid grid,
                                                       Set<Class<?>> machineTypes) {
        if (grid == null) {
            return;
        }
        Set<Object> seen = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        for (Class<?> type = grid.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || !Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collectRegisteredMachineTypes(field.get(grid), machineTypes, seen, 0);
                } catch (IllegalAccessException | SecurityException | LinkageError ignored) {
                    // An implementation may hide its registry; standard hosts remain usable.
                }
            }
        }
    }

    private static void collectRegisteredMachineTypes(Object object,
                                                        Set<Class<?>> machineTypes,
                                                        Set<Object> seen, int depth) {
        if (object == null || depth > 4 || !seen.add(object)
                || machineTypes.size() >= 256) {
            return;
        }
        if (object instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                Object key = entry.getKey();
                if (key instanceof Class && IGridHost.class.isAssignableFrom((Class<?>) key)) {
                    machineTypes.add((Class<?>) key);
                }
                collectRegisteredMachineTypes(entry.getValue(), machineTypes, seen, depth + 1);
            }
        } else if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) {
                collectRegisteredMachineTypes(value, machineTypes, seen, depth + 1);
            }
        } else if (object.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(object); index++) {
                collectRegisteredMachineTypes(Array.get(object, index), machineTypes, seen, depth + 1);
            }
        }
    }

    /**
     * Traverses only the node collection returned by IGrid#getMachines(Class).
     * It deliberately does not inspect fields or methods on arbitrary machine
     * objects; optional integrations can therefore expose their own machine
     * implementations without triggering unrelated classes during encoding.
     */
    private static int collectRegisteredGridNodes(List<InterfaceTarget> result,
                                                   Set<Object> seenMachines,
                                                   Set<Object> seenHandlers,
                                                   Object object,
                                                   Set<Object> seenNodes) {
        if (object == null || seenNodes == null || !seenNodes.add(object)) {
            return 0;
        }
        if (object instanceof IGridNode) {
            IGridNode node = (IGridNode) object;
            if (!node.isActive()) {
                return 0;
            }
            Object machine = node.getMachine();
            if (machine != null && seenMachines.add(machine)) {
                collectKnownPatternHandlers(result, seenHandlers, machine,
                        new ArrayList<Object>(),
                        Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), 0);
            }
            return 1;
        }
        try {
            int count = 0;
            if (object instanceof Map) {
                for (Object value : ((Map<?, ?>) object).values()) {
                    count += collectRegisteredGridNodes(result, seenMachines,
                            seenHandlers, value, seenNodes);
                }
            } else if (object instanceof Iterable) {
                for (Object value : (Iterable<?>) object) {
                    count += collectRegisteredGridNodes(result, seenMachines,
                            seenHandlers, value, seenNodes);
                }
            } else if (object.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(object); index++) {
                    count += collectRegisteredGridNodes(result, seenMachines,
                            seenHandlers, Array.get(object, index), seenNodes);
                }
            }
            return count;
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    /**
     * Reads only conventional pattern accessors from a network machine. This
     * mirrors the interface-terminal integration contract while avoiding
     * getDeclaredFields()/getDeclaredMethods() over the live machine graph.
     */
    private static void collectKnownPatternHandlers(List<InterfaceTarget> result,
                                                     Set<Object> seenHandlers,
                                                     Object object, List<Object> context,
                                                     Set<Object> seen, int depth) {
        if (object == null || depth > 6 || !seen.add(object)) {
            return;
        }
        if (object instanceof IItemHandler) {
            addPatternTarget(result, seenHandlers, (IItemHandler) object, context);
            return;
        }
        if (object instanceof Map) {
            for (Object value : ((Map<?, ?>) object).values()) {
                collectKnownPatternHandlers(result, seenHandlers, value, context, seen, depth + 1);
            }
            return;
        }
        if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) {
                collectKnownPatternHandlers(result, seenHandlers, value, context, seen, depth + 1);
            }
            return;
        }
        if (object.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(object); index++) {
                collectKnownPatternHandlers(result, seenHandlers, Array.get(object, index),
                        context, seen, depth + 1);
            }
            return;
        }
        if (object instanceof CharSequence || object instanceof Number
                || object instanceof Class || object.getClass().isEnum()) {
            return;
        }

        List<Object> nextContext = new ArrayList<>(context);
        nextContext.add(object);
        for (String accessor : new String[]{
                "getPatternStores", "getPatternInventory", "getPatterns",
                "getPatternHandler", "getInterfaceDuality"}) {
            Object value = invokeKnownNoArg(object, accessor);
            if (value != null && value != object) {
                collectKnownPatternHandlers(result, seenHandlers, value,
                        nextContext, seen, depth + 1);
            }
        }
        Object namedInventory = invokeKnownOneArg(object, "getInventoryByName", "patterns");
        if (namedInventory != null) {
            collectKnownPatternHandlers(result, seenHandlers, namedInventory,
                    nextContext, seen, depth + 1);
        }
    }

    private static void collectGridNodesDeep(Object object, Set<Object> seen,
                                             Set<IGridNode> nodes, int depth) {
        if (object == null || depth > 10 || !seen.add(object)) {
            return;
        }
        if (object instanceof IGridNode) {
            IGridNode node = (IGridNode) object;
            if (node.isActive()) {
                nodes.add(node);
            }
            return;
        }
        if (object instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                collectGridNodesDeep(entry.getKey(), seen, nodes, depth + 1);
                collectGridNodesDeep(entry.getValue(), seen, nodes, depth + 1);
            }
            return;
        }
        if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) {
                collectGridNodesDeep(value, seen, nodes, depth + 1);
            }
            return;
        }
        if (object.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(object); index++) {
                collectGridNodesDeep(Array.get(object, index), seen, nodes, depth + 1);
            }
            return;
        }
        if (object instanceof CharSequence || object instanceof Number
                || object instanceof Class || object.getClass().isEnum()) {
            return;
        }

        for (Class<?> type = object.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || field.getType().isPrimitive() || field.isSynthetic()) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (IllegalAccessException | SecurityException ignored) {
                    continue;
                }
                if (isGridTraversalMember(field.getName(), value)) {
                    collectGridNodesDeep(value, seen, nodes, depth + 1);
                }
            }
        }
    }

    private static boolean isGridTraversalMember(String name, Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof IGridNode || value instanceof Map || value instanceof Iterable
                || value.getClass().isArray()) {
            return true;
        }
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.contains("node") || normalized.contains("machine")
                || normalized.contains("grid") || normalized.contains("network")
                || normalized.contains("map") || normalized.contains("data")
                || normalized.contains("value") || normalized.contains("list")
                || normalized.contains("class") || normalized.contains("backing");
    }

    private static void collectPatternHandlers(List<InterfaceTarget> result,
                                               Set<Object> seenHandlers,
                                               Object object, List<Object> context,
                                               Set<Object> seen, int depth) {
        if (object == null || depth > 5 || !seen.add(object)) {
            return;
        }
        if (object instanceof IItemHandler) {
            addPatternTarget(result, seenHandlers, (IItemHandler) object, context);
            return;
        }

        List<Object> nextContext = new ArrayList<>(context);
        nextContext.add(object);
        if (object instanceof Map) {
            for (Object value : ((Map<?, ?>) object).values()) {
                collectPatternHandlers(result, seenHandlers, value, nextContext, seen, depth + 1);
            }
            return;
        }
        if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) {
                collectPatternHandlers(result, seenHandlers, value, nextContext, seen, depth + 1);
            }
            return;
        }
        if (object.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(object); index++) {
                collectPatternHandlers(result, seenHandlers, Array.get(object, index),
                        nextContext, seen, depth + 1);
            }
            return;
        }
        if (object instanceof CharSequence || object instanceof Number
                || object instanceof Class || object.getClass().isEnum()) {
            return;
        }

        for (Class<?> type = object.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                String normalized = name.toLowerCase(Locale.ROOT);
                try {
                    method.setAccessible(true);
                    Object value = null;
                    if (method.getParameterTypes().length == 0
                            && (isPatternAccessor(normalized)
                            || (IItemHandler.class.isAssignableFrom(method.getReturnType())
                            && normalized.contains("inventory")))) {
                        value = method.invoke(object);
                    } else if (method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == String.class
                            && normalized.contains("inventory")) {
                        value = method.invoke(object, "patterns");
                    }
                    if (value != null) {
                        collectPatternHandlers(result, seenHandlers, value,
                                nextContext, seen, depth + 1);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Optional integrations can expose incompatible accessors.
                }
            }
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || field.isSynthetic() || !isPatternAccessor(field.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    collectPatternHandlers(result, seenHandlers, value,
                            nextContext, seen, depth + 1);
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // Continue through other optional pattern stores.
                }
            }
        }
    }

    private static boolean isPatternAccessor(String normalizedName) {
        return normalizedName.contains("pattern") || normalizedName.contains("store");
    }

    private static void addPatternTarget(List<InterfaceTarget> result,
                                         Set<Object> seenHandlers, IItemHandler patterns,
                                         List<Object> context) {
        if (patterns == null || !seenHandlers.add(patterns) || patterns.getSlots() <= 0) {
            return;
        }
        InterfaceTarget target = new InterfaceTarget(patterns);
        target.hostClassName = context.isEmpty() ? patterns.getClass().getName()
                : context.get(0).getClass().getName();
        addLabel(target.identityLabels, patterns.getClass().getName());
        for (Object source : context) {
            addLabel(target.identityLabels, source.getClass().getName());
            addObjectLabels(target.identityLabels, source);
        }
        readExistingPatterns(target);
        result.add(target);
        LOGGER.debug("Accepted optional network pattern target {} with {} slots and labels {}.",
                target.hostClassName, patterns.getSlots(), target.identityLabels);
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
            if (existing.getItem() instanceof appeng.api.implementations.ICraftingPatternItem) {
                if (isCraftingPattern(existing)) {
                    target.hasCraftingPattern = true;
                } else {
                    target.hasProcessingPattern = true;
                }
            }
            NBTTagCompound metadata = getMachineData(existing);
            if (metadata == null) {
                continue;
            }
            addValues(target.existingMachines, metadata,
                    "MachineName", "machine", "MachineNames");
            addValues(target.existingProcessing, metadata,
                    "ProcessingMethod", "processing", "ProcessingMethods", "Methods", "methods");
            addValues(target.existingCategories, metadata,
                    "CategoryUid", "category", "Category", "categoryUid");
        }
    }

    private static void addObjectLabels(Set<String> labels, Object object) {
        if (object == null) {
            return;
        }
        for (String method : new String[]{
                "getTermName", "getCustomName", "getInterfaceName", "getMachineName",
                "getName", "getNameString", "getUnlocalizedName", "getConfigName",
                "getLabel", "getDisplayName"}) {
            addLabel(labels, invokeKnownNoArg(object, method));
        }
    }

    private static void addObjectLabels(Set<String> labels, Object object, int depth,
                                        Set<Object> seen) {
        if (object == null || depth > 2 || !seen.add(object)) {
            return;
        }
        for (Class<?> type = object.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0
                        || !isLabelMember(method.getName())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(object);
                    addLabel(labels, value);
                    if (!isSimpleLabelValue(value)) {
                        addObjectLabels(labels, value, depth + 1, seen);
                    }
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // Continue through other names exposed by this AE2 implementation.
                }
            }
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || !isLabelMember(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    addLabel(labels, value);
                    if (!isSimpleLabelValue(value)) {
                        addObjectLabels(labels, value, depth + 1, seen);
                    }
                } catch (IllegalAccessException | SecurityException ignored) {
                    // Continue through other fields exposed by this AE2 implementation.
                }
            }
        }
    }

    private static boolean isLabelMember(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("name") || normalized.contains("label")
                || normalized.contains("term") || normalized.contains("interface")
                || normalized.contains("custom") || normalized.contains("display")
                || normalized.contains("unlocalized") || normalized.contains("config")
                || normalized.contains("setting") || normalized.contains("title");
    }

    private static boolean isSimpleLabelValue(Object value) {
        return value == null || value instanceof String
                || value instanceof net.minecraft.util.text.ITextComponent
                || value instanceof Number || value instanceof Boolean
                || value.getClass().isEnum();
    }

    private static Object invokeKnownNoArg(Object object, String name) {
        if (object == null || name == null) {
            return null;
        }
        try {
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method.invoke(object);
                } catch (NoSuchMethodException ignored) {
                    // Continue through the class hierarchy.
                }
            }
            Method method = object.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(object);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {
            return null;
        }
    }

    private static Object invokeKnownOneArg(Object object, String name, Object argument) {
        if (object == null || name == null) {
            return null;
        }
        Class<?> argumentType = argument instanceof String ? String.class
                : argument == null ? Object.class : argument.getClass();
        try {
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name, argumentType);
                    method.setAccessible(true);
                    return method.invoke(object, argument);
                } catch (NoSuchMethodException ignored) {
                    // Continue through the class hierarchy.
                }
            }
            Method method = object.getClass().getMethod(name, argumentType);
            method.setAccessible(true);
            return method.invoke(object, argument);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {
            return null;
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
        } else if (value instanceof net.minecraft.util.text.ITextComponent) {
            addLabel(labels,
                    ((net.minecraft.util.text.ITextComponent) value).getUnformattedText());
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
        return root.hasKey("MachineName") || root.hasKey("machine")
                || root.hasKey("MachineNames") || root.hasKey("ProcessingMethod")
                || root.hasKey("processing") || root.hasKey("ProcessingMethods")
                || root.hasKey("CategoryUid") || root.hasKey("category") ? root : null;
    }

    private static String getCategoryUid(NBTTagCompound metadata) {
        if (metadata == null) {
            return "";
        }
        for (String key : new String[]{"CategoryUid", "category", "Category", "categoryUid"}) {
            if (!metadata.hasKey(key, 8)) {
                continue;
            }
            String value = metadata.getString(key);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
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
        try {
            Class<?> argumentType = argument == null ? Object.class : argument.getClass();
            for (Method method : object.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> parameterType = method.getParameterTypes()[0];
                if (argument == null ? parameterType.isPrimitive()
                        : !parameterType.isAssignableFrom(argumentType)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(object, argument);
                } catch (ReflectiveOperationException | SecurityException
                         | IllegalArgumentException | LinkageError ignored) {
                    // Try the next compatible public overload.
                }
            }
        } catch (SecurityException | LinkageError ignored) {
            // Optional integrations must not interrupt the encoder.
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
        private final Set<String> existingCategories = new LinkedHashSet<>();
        private boolean hasCraftingPattern;
        private boolean hasProcessingPattern;

        private InterfaceTarget(IItemHandler patterns) {
            this.patterns = patterns;
        }
    }
}
