package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.PatternTransfer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "appeng.container.implementations.ContainerInterfaceTerminal")
public abstract class MixinContainerInterfaceTerminal {
    @Inject(method = {"transferStackInSlot", "func_82846_b"}, at = @At("HEAD"),
            cancellable = true, remap = false, require = 1)
    private void ae2QuickEncodingUpload$routePattern(
            EntityPlayer player, int slotIndex, CallbackInfoReturnable<ItemStack> callback) {
        if (PatternTransfer.tryInterfaceTerminalTransfer(
                (net.minecraft.inventory.Container) (Object) this, player, slotIndex)) {
            callback.setReturnValue(ItemStack.EMPTY);
        }
    }
}
