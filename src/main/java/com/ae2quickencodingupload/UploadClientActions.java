package com.ae2quickencodingupload;

import com.ae2quickencoding.client.ClientHandler;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.util.List;

/** Shared client-side install and click path for the upload control. */
@SideOnly(Side.CLIENT)
public final class UploadClientActions {
    private UploadClientActions() {
    }

    public static void ensure(GuiScreen gui, List<GuiButton> suppliedButtons) {
        if (gui == null || !isPatternGui(gui)) {
            return;
        }
        List<GuiButton> buttons = suppliedButtons != null ? suppliedButtons : buttonList(gui);
        if (buttons == null) {
            return;
        }

        UploadButton uploadButton = findUploadButton(buttons);
        if (uploadButton == null) {
            uploadButton = new UploadButton(0, 0);
            buttons.add(uploadButton);
        }
        uploadButton.setState(ClientHandler.isSettingsMode(gui), AutoUploadSettings.isEnabled());

        GuiButton anchor = findSettingsButton(buttons);
        boolean settingsAnchor = anchor != null;
        if (anchor == null) {
            anchor = findEncodeButton(buttons);
        }
        if (anchor != null) {
            // Pattern2 target: local x=179, local y=166. The settings button is at x=164.
            uploadButton.x = anchor.x + anchor.width + (settingsAnchor ? -1 : 16);
            uploadButton.y = anchor.y;
        } else {
            uploadButton.x = readInt(gui, "guiLeft", 0) + 179;
            uploadButton.y = readInt(gui, "guiTop", 0) + 166;
        }
        uploadButton.visible = true;
        uploadButton.enabled = true;
    }

    public static boolean handle(GuiScreen gui, GuiButton button) {
        if (!(button instanceof UploadButton)) {
            return false;
        }
        UploadButton uploadButton = (UploadButton) button;
        if (uploadButton.consumeDirectAction()) {
            return true;
        }
        return dispatch(gui, uploadButton);
    }

    static boolean handleDirect(GuiScreen gui, UploadButton button) {
        if (button == null) {
            return false;
        }
        boolean handled = dispatch(gui, button);
        if (handled) {
            button.markDirectAction();
        }
        return handled;
    }

    private static boolean dispatch(GuiScreen gui, UploadButton uploadButton) {
        boolean settingsMode = false;
        if (gui != null) {
            try {
                settingsMode = ClientHandler.isSettingsMode(gui);
            } catch (Throwable ignored) {
                // A button can be clicked while the quick-encoding GUI is rebuilding.
            }
        }
        if (settingsMode) {
            boolean enabled = AutoUploadSettings.toggle();
            uploadButton.setState(true, enabled);
            UploadNetwork.sendAutomaticState(enabled);
        } else {
            UploadNetwork.sendUpload();
        }
        return true;
    }

    private static boolean isPatternGui(GuiScreen gui) {
        try {
            return ClientHandler.isPatternGui(gui);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static UploadButton findUploadButton(List<GuiButton> buttons) {
        for (GuiButton button : buttons) {
            if (button instanceof UploadButton) {
                return (UploadButton) button;
            }
        }
        return null;
    }

    private static GuiButton findSettingsButton(List<GuiButton> buttons) {
        for (GuiButton button : buttons) {
            if (button.getClass().getName().equals("com.ae2quickencoding.client.SettingsButton")
                    || button.getClass().getSimpleName().equals("SettingsButton")) {
                return button;
            }
        }
        return null;
    }

    private static GuiButton findEncodeButton(List<GuiButton> buttons) {
        for (GuiButton button : buttons) {
            if (button.getClass().getName().contains("GuiImgButton")
                    && button.width == 16 && button.height == 16) {
                return button;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttonList(GuiScreen gui) {
        for (String name : new String[]{"buttonList", "field_146292_n"}) {
            try {
                Field field = GuiScreen.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(gui);
                if (value instanceof List) {
                    return (List<GuiButton>) value;
                }
            } catch (NoSuchFieldException ignored) {
                // Try the obfuscated/deobfuscated field name.
            } catch (IllegalAccessException | SecurityException ignored) {
                return null;
            }
        }
        return null;
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
