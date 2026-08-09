package com.ae2quickencodingupload;

import appeng.client.me.ClientDCInternalInv;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves recipe categories through catalysts registered with JEI/HEI.
 * It contains no knowledge of individual mods or machines.
 */
public final class RecipeCatalystResolver {
    private static final Logger LOGGER = LogManager.getLogger("ae2_quick_encoding_upload");
    private static final Map<String, List<ItemStack>> REGISTERED_CATALYSTS =
            new ConcurrentHashMap<>();

    private RecipeCatalystResolver() {
    }

    /** Receives the exact objects passed to HEI's addRecipeCatalyst call. */
    public static void captureRecipeCatalyst(Object catalyst, String[] categoryUids) {
        if (catalyst == null || categoryUids == null || categoryUids.length == 0) {
            return;
        }
        LOGGER.info("[AE2QuickEncodingUpload] HEI catalyst hook type={} categories={}",
                catalyst.getClass().getName(), java.util.Arrays.toString(categoryUids));
        List<ItemStack> stacks = new ArrayList<>();
        collectItemStacks(catalyst, stacks);
        if (stacks.isEmpty()) {
            LOGGER.info("[AE2QuickEncodingUpload] HEI catalyst hook produced no ItemStack values type={}",
                    catalyst.getClass().getName());
            return;
        }
        for (String categoryUid : categoryUids) {
            if (categoryUid == null || categoryUid.trim().isEmpty()) {
                continue;
            }
            String key = categoryUid.trim();
            List<ItemStack> registered = REGISTERED_CATALYSTS.computeIfAbsent(
                    key, ignored -> Collections.synchronizedList(new ArrayList<>()));
            synchronized (registered) {
                for (ItemStack stack : stacks) {
                    if (!containsStackIdentity(registered, stack)) {
                        registered.add(stack.copy());
                    }
                }
            }
            LOGGER.info("[AE2QuickEncodingUpload] captured HEI catalysts category={} count={}",
                    key, registered.size());
        }
    }

    /**
     * Captures the completed category-to-catalyst table before HEI freezes it
     * into RecipeRegistry. This is the same data used to render the machine
     * list at the left side of a recipe page.
     */
    public static void captureModRegistry(Object modRegistry) {
        Object catalystTable = readNamedField(modRegistry, "recipeCatalysts");
        if (catalystTable == null) {
            LOGGER.info("[AE2QuickEncodingUpload] HEI catalyst table unavailable");
            return;
        }
        captureCatalystTable(catalystTable);
    }

    private static void captureCatalystTable(Object table) {
        Object entries = invokeNoArg(table, "entrySet");
        if (entries instanceof Iterable) {
            for (Object entry : (Iterable<?>) entries) {
                if (entry instanceof Map.Entry) {
                    Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entry;
                    captureRecipeCatalyst(pair.getValue(), new String[]{String.valueOf(pair.getKey())});
                }
            }
            return;
        }

        Object keys = invokeNoArg(table, "keySet");
        if (keys instanceof Iterable) {
            for (Object key : (Iterable<?>) keys) {
                Object values = invokeOneArg(table, "get", key);
                captureRecipeCatalyst(values, new String[]{String.valueOf(key)});
            }
        }
    }

    public static List<ItemStack> getCatalysts(String categoryUid) {
        if (categoryUid == null || categoryUid.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<ItemStack> catalysts = new ArrayList<>();
        List<ItemStack> registered = REGISTERED_CATALYSTS.get(categoryUid.trim());
        if (registered != null) {
            synchronized (registered) {
                appendUniqueStacks(catalysts, registered);
            }
            LOGGER.info("[AE2QuickEncodingUpload] cached catalysts category={} count={}",
                    categoryUid, catalysts.size());
        }

        try {
            Class<?> jeiClass = Class.forName("mezz.jei.JustEnoughItems");
            Object proxy = invokeStaticNoArg(jeiClass, "getProxy");
            Object runtime = invokeNoArg(proxy, "getRuntime");
            if (runtime == null) {
                runtime = findObjectByType(proxy, "mezz.jei.api.IJeiRuntime", new HashSet<Object>());
            }
            Object recipeRegistry = invokeNoArg(runtime, "getRecipeRegistry");
            if (recipeRegistry == null) {
                recipeRegistry = findObjectByType(proxy, "mezz.jei.api.IRecipeRegistry", new HashSet<Object>());
            }
            if (recipeRegistry == null) {
                LOGGER.info("[AE2QuickEncodingUpload] HEI recipe registry unavailable category={}",
                        categoryUid);
                return catalysts;
            }

            Object category = invokeStringArg(recipeRegistry, "getRecipeCategory", categoryUid);
            if (category == null) {
                category = findCategoryIgnoreCase(recipeRegistry, categoryUid);
            }
            if (category == null) {
                LOGGER.info("[AE2QuickEncodingUpload] recipe category not registered: {}", categoryUid);
                return catalysts;
            }

            Object rawCatalysts = invokeCompatibleArg(recipeRegistry, "getRecipeCatalysts", category);
            List<ItemStack> runtimeCatalysts = new ArrayList<>();
            collectItemStacks(rawCatalysts, runtimeCatalysts);
            appendUniqueStacks(catalysts, runtimeCatalysts);
            LOGGER.info("[AE2QuickEncodingUpload] runtime catalysts category={} count={}",
                    categoryUid, catalysts.size());
            return catalysts;
        } catch (Throwable error) {
            LOGGER.debug("[AE2QuickEncodingUpload] could not read recipe catalysts for category "
                    + categoryUid, error);
            return catalysts;
        }
    }

    /** Adds the raw names of every HEI-left-list machine to pattern metadata. */
    public static void appendMachineAliases(NBTTagCompound machineData, String categoryUid) {
        if (machineData == null || categoryUid == null || categoryUid.trim().isEmpty()) {
            return;
        }
        NBTTagList aliases = machineData.hasKey("MachineNames", 9)
                ? machineData.getTagList("MachineNames", 8) : new NBTTagList();
        for (ItemStack catalyst : getCatalysts(categoryUid)) {
            for (String alias : rawAliases(catalyst)) {
                appendAlias(aliases, alias);
            }
        }
        if (aliases.tagCount() > 0) {
            machineData.setTag("MachineNames", aliases);
        }
    }

    /** Matches complete item identities only; no substring or root matching. */
    public static boolean matchesAny(List<ItemStack> catalysts, ClientDCInternalInv inventory) {
        if (catalysts == null || catalysts.isEmpty() || inventory == null) {
            return false;
        }
        String displayName = inventory.getName();
        String unlocalizedName = inventory.getUnlocalizedName();
        for (ItemStack catalyst : catalysts) {
            if (matches(catalyst, displayName) || matches(catalyst, unlocalizedName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns every stable identity exposed by a catalyst, including the
     * damage value. HEI can expose a factory tier as a catalyst stack whose
     * display name is localized, while the interface only exposes its
     * registry/unlocalized identity.
     */
    public static Set<String> identitiesFor(ItemStack stack) {
        return identities(stack);
    }

    private static boolean matches(ItemStack catalyst, String candidate) {
        if (catalyst == null || catalyst.isEmpty() || candidate == null) {
            return false;
        }
        return identities(catalyst).contains(normalize(candidate));
    }

    private static Set<String> identities(ItemStack stack) {
        Set<String> result = new LinkedHashSet<>();
        add(result, stack.getDisplayName());
        add(result, stack.getItemDamage() == 0 ? "" : Integer.toString(stack.getItemDamage()));
        Item item = stack.getItem();
        if (item != null) {
            add(result, item.getUnlocalizedName(stack));
            add(result, item.getUnlocalizedName());
            ResourceLocation registryName = item.getRegistryName();
            if (registryName != null) {
                add(result, registryName.toString());
                add(result, registryName.getResourcePath());
            }
        }
        return result;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                result.add(stack.copy());
            }
        }
        return result;
    }

    private static boolean containsStackIdentity(List<ItemStack> stacks, ItemStack candidate) {
        for (ItemStack existing : stacks) {
            if (sameCatalyst(existing, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static void appendUniqueStacks(List<ItemStack> target, List<ItemStack> additions) {
        for (ItemStack stack : additions) {
            if (stack != null && !stack.isEmpty() && !containsStackIdentity(target, stack)) {
                target.add(stack.copy());
            }
        }
    }

    private static boolean sameCatalyst(ItemStack first, ItemStack second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) {
            return false;
        }
        if (first.getItem() == second.getItem()
                && first.getItemDamage() == second.getItemDamage()) {
            return true;
        }
        String firstDisplay = normalize(first.getDisplayName());
        String secondDisplay = normalize(second.getDisplayName());
        return !firstDisplay.isEmpty() && firstDisplay.equals(secondDisplay);
    }

    private static List<String> rawAliases(ItemStack stack) {
        List<String> result = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return result;
        }
        addRaw(result, stack.getDisplayName());
        Item item = stack.getItem();
        if (item != null) {
            addRaw(result, item.getUnlocalizedName(stack));
            addRaw(result, item.getUnlocalizedName());
            ResourceLocation registryName = item.getRegistryName();
            if (registryName != null) {
                addRaw(result, registryName.toString());
                addRaw(result, registryName.getResourcePath());
            }
        }
        return result;
    }

    private static void addRaw(List<String> values, String value) {
        if (value != null && !value.trim().isEmpty() && !values.contains(value)) {
            values.add(value);
        }
    }

    private static void appendAlias(NBTTagList aliases, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String normalized = normalize(value);
        for (int index = 0; index < aliases.tagCount(); index++) {
            if (normalized.equals(normalize(aliases.getStringTagAt(index)))) {
                return;
            }
        }
        aliases.appendTag(new net.minecraft.nbt.NBTTagString(value));
    }

    private static void add(Set<String> target, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) {
            target.add(normalized);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        value = stripFormattingCodes(value);
        return value.replaceAll("(?i)§[0-9a-fk-or]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String stripFormattingCodes(String value) {
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

    private static Object findCategoryIgnoreCase(Object recipeRegistry, String categoryUid) {
        Object categories = invokeNoArg(recipeRegistry, "getRecipeCategories");
        if (!(categories instanceof Iterable)) {
            return null;
        }
        String expected = normalize(categoryUid);
        for (Object category : (Iterable<?>) categories) {
            Object uid = invokeNoArg(category, "getUid");
            if (uid != null && expected.equals(normalize(String.valueOf(uid)))) {
                return category;
            }
        }
        return null;
    }

    private static void collectItemStacks(Object value, List<ItemStack> result) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack) {
            ItemStack stack = (ItemStack) value;
            if (!stack.isEmpty()) {
                result.add(stack.copy());
            }
            return;
        }
        if (value instanceof Item) {
            result.add(new ItemStack((Item) value));
            return;
        }
        if (value instanceof Block) {
            result.add(new ItemStack((Block) value));
            return;
        }
        if (value instanceof Iterable) {
            for (Object element : (Iterable<?>) value) {
                collectItemStacks(element, result);
            }
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectItemStacks(Array.get(value, index), result);
            }
            return;
        }
        if (value instanceof Map) {
            for (Object element : ((Map<?, ?>) value).values()) {
                collectItemStacks(element, result);
            }
        }
    }

    private static Object findObjectByType(Object root, String typeName, Set<Object> visited) {
        if (root == null || visited.contains(root)) {
            return null;
        }
        visited.add(root);
        if (implementsTypeNamed(root.getClass(), typeName)) {
            return root;
        }
        for (Class<?> type = root.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object found = findObjectByType(field.get(root), typeName, visited);
                    if (found != null) {
                        return found;
                    }
                } catch (IllegalAccessException | SecurityException ignored) {
                    // Continue through the proxy object graph.
                }
            }
        }
        return null;
    }

    private static Object readNamedField(Object object, String name) {
        if (object == null) {
            return null;
        }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // Continue through the class hierarchy.
            } catch (IllegalAccessException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeOneArg(Object target, String name, Object argument) {
        if (target == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 1
                    || (argument != null && !method.getParameterTypes()[0].isAssignableFrom(argument.getClass()))) {
                continue;
            }
            try {
                return method.invoke(target, argument);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean implementsTypeNamed(Class<?> type, String typeName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getName().equals(typeName) || implementsTypeNamed(iface, typeName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object invokeStaticNoArg(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 0
                    || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                return method.invoke(null);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 0) {
                continue;
            }
            try {
                return method.invoke(target);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeStringArg(Object target, String name, String value) {
        if (target == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!method.getName().equals(name) || parameterTypes.length != 1
                    || parameterTypes[0] != String.class) {
                continue;
            }
            try {
                return method.invoke(target, value);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeCompatibleArg(Object target, String name, Object argument) {
        if (target == null || argument == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!method.getName().equals(name) || parameterTypes.length != 1
                    || !parameterTypes[0].isAssignableFrom(argument.getClass())) {
                continue;
            }
            try {
                return method.invoke(target, argument);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }
}
