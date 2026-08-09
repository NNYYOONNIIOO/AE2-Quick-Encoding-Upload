package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.PatternTransfer;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "appeng.client.gui.implementations.GuiInterfaceTerminal")
public abstract class MixinGuiInterfaceTerminal {
    @Inject(method = "postUpdate", at = @At("TAIL"), remap = false, require = 1)
    private void ae2QuickEncodingUpload$captureInterfaces(
            NBTTagCompound data, CallbackInfo callback) {
        PatternTransfer.captureGuiInterfaceInventories(this);
    }
}

