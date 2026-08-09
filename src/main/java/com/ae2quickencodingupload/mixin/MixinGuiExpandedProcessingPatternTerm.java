package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.UploadClientActions;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Direct fallback for the expanded processing pattern terminal. */
@Pseudo
@Mixin(targets = "appeng.client.gui.implementations.GuiExpandedProcessingPatternTerm")
public abstract class MixinGuiExpandedProcessingPatternTerm {
    @Inject(method = "initGui", at = @At("TAIL"), remap = false, require = 0)
    private void ae2QuickEncodingUpload$installButton(CallbackInfo callback) {
        UploadClientActions.ensure((GuiScreen) (Object) this, null);
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), remap = false, require = 0, cancellable = true)
    private void ae2QuickEncodingUpload$handleButton(GuiButton button, CallbackInfo callback) {
        if (UploadClientActions.handle((GuiScreen) (Object) this, button)) {
            callback.cancel();
        }
    }
}
