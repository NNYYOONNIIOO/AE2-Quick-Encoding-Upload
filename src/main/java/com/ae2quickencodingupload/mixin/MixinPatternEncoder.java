package com.ae2quickencodingupload.mixin;

import com.ae2quickencoding.model.PatternData;
import com.ae2quickencodingupload.MachineMetadata;
import com.ae2quickencodingupload.PendingPatternMachineData;
import com.ae2quickencodingupload.PatternMachineDataAccess;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.ae2quickencoding.server.PatternEncoder")
public abstract class MixinPatternEncoder {
    @Inject(method = "encode", at = @At("HEAD"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$applyPendingMachineData(
            EntityPlayerMP player, PatternData data, CallbackInfoReturnable<Boolean> callback) {
        NBTTagCompound machineData = PendingPatternMachineData.take(player);
        Object rawData = data;
        if (rawData instanceof PatternMachineDataAccess && machineData != null && !machineData.hasNoTags()) {
            ((PatternMachineDataAccess) rawData).ae2QuickEncodingUpload$setMachineData(machineData);
        }
    }

    @Inject(method = "createPatternTag", at = @At("RETURN"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$writeMachineData(
            PatternData data, boolean fluidPattern,
            CallbackInfoReturnable<NBTTagCompound> callback) {
        Object rawData = data;
        if (!(rawData instanceof PatternMachineDataAccess)) {
            return;
        }
        NBTTagCompound machineData = ((PatternMachineDataAccess) rawData)
                .ae2QuickEncodingUpload$getMachineData();
        if (machineData != null && !machineData.hasNoTags()) {
            callback.getReturnValue().setTag(MachineMetadata.NBT_KEY, machineData.copy());
        }
    }
}
