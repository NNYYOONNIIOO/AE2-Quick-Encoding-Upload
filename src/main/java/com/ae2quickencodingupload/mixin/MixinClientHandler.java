package com.ae2quickencodingupload.mixin;

import com.ae2quickencoding.client.ClientHandler;
import com.ae2quickencodingupload.AutoUploadSettings;
import com.ae2quickencodingupload.UploadButton;
import com.ae2quickencodingupload.UploadNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Integrates the upload button with Quick Encoding's own button lifecycle. */
@Pseudo
@Mixin(targets = "com.ae2quickencoding.client.ClientHandler")
public abstract class MixinClientHandler {
    @Inject(method = "ensureQuickEncodingControls", at = @At("TAIL"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$ensureButton(
            GuiScreen gui, List<GuiButton> buttons, CallbackInfo callback) {
        if (gui == null || buttons == null || !ClientHandler.isPatternGui(gui)) {
            return;
        }

        UploadButton uploadButton = ae2QuickEncodingUpload$findButton(buttons);
        boolean created = uploadButton == null;
        if (created) {
            uploadButton = new UploadButton(0, 0);
            buttons.add(uploadButton);
        }

        uploadButton.setState(ClientHandler.isSettingsMode(gui), AutoUploadSettings.isEnabled());
        GuiButton anchor = ae2QuickEncodingUpload$findSettingsButton(buttons);
        if (anchor == null) {
            anchor = ae2QuickEncodingUpload$findEncodeButton(buttons);
        }
        if (anchor != null) {
            uploadButton.x = anchor.x + anchor.width + 1;
            uploadButton.y = anchor.y;
        }
        uploadButton.visible = true;
        uploadButton.enabled = true;
        if (created) {
            UploadNetwork.sendAutomaticState(AutoUploadSettings.isEnabled());
        }
    }

    @Inject(method = "onGuiAction", at = @At("HEAD"), remap = false, require = 0, cancellable = true)
    private void ae2QuickEncodingUpload$handleButton(
            GuiScreenEvent.ActionPerformedEvent event, CallbackInfo callback) {
        if (event == null || !(event.getButton() instanceof UploadButton)) {
            return;
        }
        GuiScreen gui = event.getGui();
        if (gui == null || !ClientHandler.isPatternGui(gui)) {
            return;
        }

        if (ClientHandler.isSettingsMode(gui)) {
            boolean enabled = AutoUploadSettings.toggle();
            ((UploadButton) event.getButton()).setState(true, enabled);
            UploadNetwork.sendAutomaticState(enabled);
        } else {
            UploadNetwork.sendUpload();
        }
        event.setCanceled(true);
        callback.cancel();
    }

    @Unique
    private static UploadButton ae2QuickEncodingUpload$findButton(List<GuiButton> buttons) {
        for (GuiButton button : buttons) {
            if (button instanceof UploadButton) {
                return (UploadButton) button;
            }
        }
        return null;
    }

    @Unique
    private static GuiButton ae2QuickEncodingUpload$findSettingsButton(List<GuiButton> buttons) {
        for (GuiButton button : buttons) {
            if (button.getClass().getName().equals("com.ae2quickencoding.client.SettingsButton")
                    || button.getClass().getSimpleName().equals("SettingsButton")) {
                return button;
            }
        }
        return null;
    }

    @Unique
    private static GuiButton ae2QuickEncodingUpload$findEncodeButton(List<GuiButton> buttons) {
        for (GuiButton button : buttons) {
            String name = button.getClass().getName();
            if (name.contains("GuiImgButton") && button.width == 16 && button.height == 16) {
                return button;
            }
        }
        return null;
    }
}
