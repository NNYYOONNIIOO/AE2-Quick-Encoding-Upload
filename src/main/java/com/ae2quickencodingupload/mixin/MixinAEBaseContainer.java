package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.PatternTransfer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AE2 containers do not dispatch their Shift-click method through the vanilla
 * Container implementation, so route at AE2's common container layer too.
 */
@Pseudo
@Mixin(targets = "appeng.container.AEBaseContainer")
public abstract class MixinAEBaseContainer {
    @Inject(method = {"transferStackInSlot", "func_82846_b"}, at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private void ae2QuickEncodingUpload$routePattern(
            EntityPlayer player, int slotIndex, CallbackInfoReturnable<ItemStack> callback) {
        ItemStack moved = PatternTransfer.tryTransfer((Container) (Object) this, player, slotIndex);
        if (!moved.isEmpty()) {
            callback.setReturnValue(moved);
        }
    }
}
