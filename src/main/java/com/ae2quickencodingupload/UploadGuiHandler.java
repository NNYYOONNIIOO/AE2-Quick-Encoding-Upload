package com.ae2quickencodingupload;

import com.ae2quickencoding.client.ClientHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.util.List;

/** Adds and maintains the upload control on every pattern-terminal GUI rebuild. */
@SideOnly(Side.CLIENT)
public final class UploadGuiHandler {
    private static final int BUTTON_ID = 0xAE2001;

    @SubscribeEvent
    public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        GuiScreen gui = event.getGui();
        if (!isPatternGui(gui)) {
            return;
        }
        removeUploadButtons(event.getButtonList());
        UploadButton button = new UploadButton(0, 0);
        event.getButtonList().add(button);
        sync(gui, button);
        UploadNetwork.sendAutomaticState(AutoUploadSettings.isEnabled());
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(event.getButton() instanceof UploadButton) || !isPatternGui(event.getGui())) {
            return;
        }
        UploadButton button = (UploadButton) event.getButton();
        if (isSettingsMode(event.getGui())) {
            boolean enabled = AutoUploadSettings.toggle();
            button.setState(true, enabled);
            UploadNetwork.sendAutomaticState(enabled);
        } else {
            UploadNetwork.sendUpload();
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        GuiScreen gui = event.getGui();
        if (!isPatternGui(gui)) {
            return;
        }
        List<GuiButton> buttons = buttonList(gui);
        UploadButton button = find(buttons);
        if (button == null && buttons != null) {
            button = new UploadButton(0, 0);
            buttons.add(button);
        }
        if (button != null) {
            sync(gui, button);
        }
    }

    private static boolean isPatternGui(GuiScreen gui) {
        if (gui == null) {
            return false;
        }
        try {
            if (ClientHandler.isPatternGui(gui)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Compatibility with quick-encoding releases with different client APIs.
        }
        String name = gui.getClass().getName();
        return name.contains("GuiPatternTerm") || name.contains("GuiProcessingPatternTerm");
    }

    private static boolean isSettingsMode(GuiScreen gui) {
        try {
            return ClientHandler.isSettingsMode(gui);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sync(GuiScreen gui, UploadButton button) {
        button.setState(isSettingsMode(gui), AutoUploadSettings.isEnabled());
        int left = readInt(gui, "guiLeft", 0);
        int top = readInt(gui, "guiTop", 0);
        int width = readInt(gui, "xSize", 176);
        button.x = left + width + 8;
        button.y = top + findPatternInventoryTop(gui);
        button.visible = true;
        button.enabled = true;
    }

    private static int findPatternInventoryTop(GuiScreen gui) {
        Container container = findContainer(gui);
        if (container != null) {
            int best = Integer.MAX_VALUE;
            for (Slot slot : container.inventorySlots) {
                if (slot.getClass().getName().contains("SlotME") && slot.yPos >= 80) {
                    best = Math.min(best, slot.yPos);
                }
            }
            if (best != Integer.MAX_VALUE) {
                return best;
            }
        }
        return readInt(gui, "ySize", 166);
    }

    private static Container findContainer(GuiScreen gui) {
        if (!(gui instanceof GuiContainer)) {
            return null;
        }
        for (Class<?> type = gui.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(gui);
                    if (value instanceof Container) {
                        return (Container) value;
                    }
                } catch (IllegalAccessException | SecurityException ignored) {
                    // Continue looking for the container.
                }
            }
        }
        return null;
    }

    private static void removeUploadButtons(List<GuiButton> buttons) {
        if (buttons == null) {
            return;
        }
        buttons.removeIf(button -> button instanceof UploadButton || button.id == BUTTON_ID);
    }

    private static UploadButton find(List<GuiButton> buttons) {
        if (buttons == null) {
            return null;
        }
        for (GuiButton button : buttons) {
            if (button instanceof UploadButton) {
                return (UploadButton) button;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttonList(GuiScreen gui) {
        try {
            Field field = GuiScreen.class.getDeclaredField("buttonList");
            field.setAccessible(true);
            return (List<GuiButton>) field.get(gui);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static int readInt(Object object, String name, int fallback) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(object);
                return value instanceof Number ? ((Number) value).intValue() : fallback;
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            } catch (IllegalAccessException | SecurityException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
