package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.UploadClientActions;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Hooks the same Quick Encoding lifecycle that rebuilds its controls after GUI refreshes. */
@Pseudo
@Mixin(targets = "com.ae2quickencoding.client.ClientHandler")
public abstract class MixinClientHandler {
    @Inject(method = "ensureQuickEncodingControls", at = @At("TAIL"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$ensureButton(
            GuiScreen gui, List<GuiButton> buttons, CallbackInfo callback) {
        UploadClientActions.ensure(gui, buttons);
    }
}
