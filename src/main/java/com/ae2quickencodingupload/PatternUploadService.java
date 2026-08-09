package com.ae2quickencodingupload;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
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

/** Server-side movement of encoded patterns into all matching ME interfaces on the open grid. */
public final class PatternUploadService {
    private PatternUploadService() {
    }

    public static int uploadInventory(EntityPlayerMP player) {
        if (player == null || player.openContainer == null || !isPatternContainer(player.openContainer)) {
            return 0;
        }
        Object grid = findGrid(player.openContainer);
        if (grid == null) {
            return 0;
        }
        List<InterfaceTarget> targets = findTargets(grid);
        if (targets.isEmpty()) {
            return 0;
        }
        int moved = uploadList(player.inventory.mainInventory, targets);
        moved += uploadList(player.inventory.offHandInventory, targets);
        moved += uploadContainerSlots(player, targets);
        if (moved > 0) {
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
        }
        return moved;
    }

    private static int uploadContainerSlots(EntityPlayerMP player, List<InterfaceTarget> targets) {
        if (player.openContainer == null || player.openContainer.inventorySlots == null) {
            return 0;
        }
        int moved = 0;
        for (Slot slot : player.openContainer.inventorySlots) {
            if (slot == null) {
                continue;
            }
            ItemStack source = slot.getStack();
            if (!isUploadable(source)) {
                continue;
            }
            int before = source.getCount();
            ItemStack remaining = source.copy();
            for (InterfaceTarget target : targets) {
                if (remaining.isEmpty() || !matches(remaining, target)) {
                    continue;
                }
                remaining = insertIntoEmptyPatternSlots(target.patterns, remaining);
            }
            if (remaining.getCount() < before) {
                moved += before - remaining.getCount();
                slot.putStack(remaining.isEmpty() ? ItemStack.EMPTY : remaining);
                slot.onSlotChanged();
            }
        }
        return moved;
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
            for (InterfaceTarget target : targets) {
                if (remaining.isEmpty() || !matches(remaining, target)) {
                    continue;
                }
                ItemStack after = insertIntoEmptyPatternSlots(target.patterns, remaining);
                if (after.getCount() < remaining.getCount()) {
                    moved += remaining.getCount() - after.getCount();
                    remaining = after;
                }
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

    private static boolean matches(ItemStack pattern, InterfaceTarget target) {
        if (isCraftingPattern(pattern)) {
            return target.hasCraftingPattern || hasGenericCraftingName(target.labels);
        }
        NBTTagCompound metadata = getMachineData(pattern);
        if (metadata == null) {
            return false;
        }
        String category = first(metadata, "CategoryUid", "category", "categoryUid", "recipeCategory");
        if (!category.isEmpty() && target.categories.contains(normalize(category))) {
            return true;
        }
        Set<String> aliases = new LinkedHashSet<>();
        addValues(aliases, metadata,
                "MachineName", "machine", "MachineNames",
                "ProcessingMethod", "processing", "ProcessingMethods", "Methods", "methods",
                "CategoryUid", "category", "categoryUid", "recipeCategory");
        for (String alias : aliases) {
            if (target.labels.contains(normalize(alias))) {
                return true;
            }
        }
        return false;
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

    private static List<InterfaceTarget> findTargets(Object grid) {
        List<InterfaceTarget> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (String className : new String[]{
                "appeng.parts.misc.PartInterface", "appeng.tile.misc.TileInterface",
                "appeng.api.networking.IGridHost"}) {
            try {
                Object machines = invokeOneArg(grid, "getMachines", Class.forName(className));
                List<Object> hosts = new ArrayList<>();
                addObjects(hosts, machines);
                for (Object host : hosts) {
                    if (host == null || !seen.add(host)) {
                        continue;
                    }
                    Object duality = invokeNoArg(host, "getInterfaceDuality");
                    IItemHandler patterns = asHandler(invokeNoArg(duality, "getPatterns"));
                    if (patterns == null) {
                        patterns = asHandler(invokeNoArg(host, "getPatterns"));
                    }
                    if (patterns == null) {
                        continue;
                    }
                    InterfaceTarget target = new InterfaceTarget(patterns);
                    addObjectLabels(target.labels, host);
                    addObjectLabels(target.labels, duality);
                    addStackLabels(target.labels, invokeNoArg(host, "getItemStackRepresentation"));
                    addStackLabels(target.labels, invokeNoArg(duality, "getItemStackRepresentation"));
                    readHandlerLabels(target.labels, asHandler(invokeNoArg(duality, "getConfig")));
                    readExistingPatterns(target);
                    result.add(target);
                }
            } catch (ClassNotFoundException ignored) {
                // This AE2 build does not expose one of the two interface host classes.
            }
        }
        return result;
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
            String category = first(metadata, "CategoryUid", "category", "categoryUid", "recipeCategory");
            if (!category.isEmpty()) {
                target.categories.add(normalize(category));
            }
            addValues(target.labels, metadata,
                    "MachineName", "machine", "MachineNames",
                    "ProcessingMethod", "processing", "ProcessingMethods", "Methods", "methods",
                    "CategoryUid", "category", "categoryUid", "recipeCategory");
        }
    }

    private static void addObjectLabels(Set<String> labels, Object object) {
        if (object == null) {
            return;
        }
        for (String method : new String[]{
                "getCustomName", "getName", "getInterfaceName", "getUnlocalizedName"}) {
            addLabel(labels, invokeNoArg(object, method));
        }
        for (String field : new String[]{"customName", "name", "interfaceName", "unlocalizedName"}) {
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

    private static Object findGrid(Container container) {
        return findGridDeep(container,
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), 0);
    }

    private static Object findGridDeep(Object object, Set<Object> seen, int depth) {
        if (object == null || depth > 6 || !seen.add(object)) {
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
        Object grid = invokeNoArg(object, "getGrid");
        if (grid != null) {
            return grid;
        }
        Object node = invokeNoArg(object, "getNetworkNode");
        if (node == null) {
            node = invokeNoArg(object, "getGridNode");
        }
        grid = invokeNoArg(node, "getGrid");
        if (grid != null) {
            return grid;
        }
        Object proxy = invokeNoArg(object, "getProxy");
        return invokeNoArg(proxy, "getGrid");
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
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    return null;
                }
            }
        }
        for (Method method : object.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 1) {
                continue;
            }
            try {
                return method.invoke(object, argument);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
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
        private final Set<String> labels = new LinkedHashSet<>();
        private final Set<String> categories = new LinkedHashSet<>();
        private boolean hasCraftingPattern;

        private InterfaceTarget(IItemHandler patterns) {
            this.patterns = patterns;
        }
    }
}
