package com.ae2quickencodingupload.mixin;

import com.ae2quickencoding.network.EncodeRecipeMessage;
import com.ae2quickencodingupload.MessageMachineDataAccess;
import com.ae2quickencodingupload.PendingPatternMachineData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.ae2quickencoding.network.EncodeRecipeMessage$Handler")
public abstract class MixinEncodeRecipeMessageHandler {
    @Inject(method = "onMessage", at = @At("HEAD"), remap = false, require = 0)
    private void ae2QuickEncodingUpload$queueMachineData(
            EncodeRecipeMessage message,
            MessageContext context,
            CallbackInfoReturnable<IMessage> callback) {
        if (context == null || context.getServerHandler() == null
                || !(message instanceof MessageMachineDataAccess)) {
            return;
        }
        EntityPlayerMP player = context.getServerHandler().player;
        PendingPatternMachineData.enqueue(player,
                ((MessageMachineDataAccess) message).ae2QuickEncodingUpload$getMachineData());
    }
}
