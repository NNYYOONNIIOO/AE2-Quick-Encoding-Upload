package com.ae2quickencodingupload.mixin;

import com.ae2quickencodingupload.PatternMachineDataAccess;
import com.ae2quickencoding.model.PatternData;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PatternData.class)
public abstract class MixinPatternData implements PatternMachineDataAccess {
    @Unique
    private NBTTagCompound ae2QuickEncodingUpload$machineData;

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
