package com.ae2quickencodingupload;

import appeng.client.me.ClientDCInternalInv;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves recipe categories through catalysts registered with JEI/HEI.
 * It contains no knowledge of individual mods or machines.
 */
public final class RecipeCatalystResolver {
    private static final Logger LOGGER = LogManager.getLogger("ae2_quick_encoding_upload");

    private RecipeCatalystResolver() {
    }

    public static List<ItemStack> getCatalysts(String categoryUid) {
        if (categoryUid == null || categoryUid.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Class<?> jeiClass = Class.forName("mezz.jei.JustEnoughItems");
            Object runtime = invokeStaticNoArg(jeiClass, "getRuntime");
            Object recipeRegistry = invokeNoArg(runtime, "getRecipeRegistry");
            if (recipeRegistry == null) {
                return Collections.emptyList();
            }

            Object category = invokeStringArg(recipeRegistry, "getRecipeCategory", categoryUid);
            if (category == null) {
                category = findCategoryIgnoreCase(recipeRegistry, categoryUid);
            }
            if (category == null) {
                LOGGER.debug("[AE2QuickEncodingUpload] recipe category not registered: {}", categoryUid);
                return Collections.emptyList();
            }

            Object rawCatalysts = invokeCompatibleArg(recipeRegistry, "getRecipeCatalysts", category);
            List<ItemStack> catalysts = new ArrayList<>();
            collectItemStacks(rawCatalysts, catalysts);
            return catalysts.isEmpty() ? Collections.emptyList() : catalysts;
        } catch (Throwable error) {
            LOGGER.debug("[AE2QuickEncodingUpload] could not read recipe catalysts for category "
                    + categoryUid, error);
            return Collections.emptyList();
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

    private static boolean matches(ItemStack catalyst, String candidate) {
        if (catalyst == null || catalyst.isEmpty() || candidate == null) {
            return false;
        }
        return identities(catalyst).contains(normalize(candidate));
    }

    private static Set<String> identities(ItemStack stack) {
        Set<String> result = new LinkedHashSet<>();
        add(result, stack.getDisplayName());
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
        }
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
