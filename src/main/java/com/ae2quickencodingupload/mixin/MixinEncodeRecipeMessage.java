package com.ae2quickencodingupload.mixin;

import com.ae2quickencoding.model.PatternData;
import com.ae2quickencodingupload.MessageMachineDataAccess;
import com.ae2quickencodingupload.PatternMachineDataAccess;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.ae2quickencoding.network.EncodeRecipeMessage")
public abstract class MixinEncodeRecipeMessage implements MessageMachineDataAccess {
    @Unique
    private NBTTagCompound ae2QuickEncodingUpload$machineData;

    @Inject(method = "<init>(ILcom/ae2quickencoding/model/PatternData;Z)V", at = @At("RETURN"), remap = false)
    private void ae2QuickEncodingUpload$copyMachineData(
            int requestId, PatternData data, boolean removeBookmark, CallbackInfo callback) {
        Object rawData = data;
        if (rawData instanceof PatternMachineDataAccess) {
            ae2QuickEncodingUpload$setMachineData(
                    ((PatternMachineDataAccess) rawData).ae2QuickEncodingUpload$getMachineData());
        }
    }

    @Inject(method = "toBytes", at = @At("TAIL"), remap = false)
    private void ae2QuickEncodingUpload$writeMachineData(ByteBuf buffer, CallbackInfo callback) {
        ByteBufUtils.writeTag(buffer, ae2QuickEncodingUpload$machineData == null
                ? new NBTTagCompound() : ae2QuickEncodingUpload$machineData);
    }

    @Inject(method = "fromBytes", at = @At("TAIL"), remap = false)
    private void ae2QuickEncodingUpload$readMachineData(ByteBuf buffer, CallbackInfo callback) {
        if (buffer.isReadable()) {
            ae2QuickEncodingUpload$machineData = ByteBufUtils.readTag(buffer);
        }
    }

    @Override
    public NBTTagCompound ae2QuickEncodingUpload$getMachineData() {
        return ae2QuickEncodingUpload$machineData == null
                ? null : ae2QuickEncodingUpload$machineData.copy();
    }

    @Override
    public void ae2QuickEncodingUpload$setMachineData(NBTTagCompound machineData) {
        ae2QuickEncodingUpload$machineData = machineData == null ? null : machineData.copy();
    }
}
