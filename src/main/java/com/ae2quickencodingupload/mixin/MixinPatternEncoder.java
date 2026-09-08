package com.ae2quickencodingupload.mixin;

import com.ae2quickencoding.model.PatternData;
import com.ae2quickencodingupload.AutoUploadState;
import com.ae2quickencodingupload.MachineMetadata;
import com.ae2quickencodingupload.PendingPatternMachineData;
import com.ae2quickencodingupload.PatternMachineDataAccess;
import com.ae2quickencodingupload.PatternUploadService;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.ae2quickencoding.server.PatternEncoder")
public abstract class MixinPatternEncoder {
    private static final Logger LOGGER = LogManager.getLogger("ae2_quick_encoding_upload");

    @Inject(method = "encode", at = @At("HEAD"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$applyPendingMachineData(
            EntityPlayerMP player, PatternData data, CallbackInfoReturnable<Boolean> callback) {
        Object rawData = data;
        NBTTagCompound machineData = PendingPatternMachineData.take(player);
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

    @Inject(method = "encode",
            at = @At("RETURN"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$autoUpload(
            EntityPlayerMP player, PatternData data, CallbackInfoReturnable<Boolean> callback) {
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "Automatic upload hook reached for {}: success={}, enabled={}",
                player.getName(), callback.getReturnValue(), AutoUploadState.isEnabled(player));
        if (callback.getReturnValue()) {
            ae2QuickEncodingUpload$tryAutoUpload(player, "encode-return");
        }
    }

    @Inject(method = "storePattern", at = @At("RETURN"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$afterStorePattern(
            EntityPlayerMP player, int blankSlot, int destination, ItemStack result,
            CallbackInfo callback) {
        ae2QuickEncodingUpload$tryAutoUpload(player, "store-pattern");
    }

    @Inject(method = "storePatternInPlayer", at = @At("RETURN"), remap = false, require = 0)
    private static void ae2QuickEncodingUpload$afterStorePatternInPlayer(
            EntityPlayerMP player, int destination, ItemStack result,
            CallbackInfo callback) {
        ae2QuickEncodingUpload$tryAutoUpload(player, "store-pattern-in-player");
    }

    private static void ae2QuickEncodingUpload$tryAutoUpload(
            final EntityPlayerMP player, String source) {
        if (player == null || !AutoUploadState.isEnabled(player)) {
            return;
        }
        int moved = PatternUploadService.uploadInventory(player);
        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "Automatic pattern upload source={} player={} moved={}",
                source, player.getName(), moved);
        if (moved == 0) {
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (!AutoUploadState.isEnabled(player)) {
                        return;
                    }
                    int retry = PatternUploadService.uploadInventory(player);
                    com.ae2quickencodingupload.DebugLogger.info(LOGGER, "Deferred automatic pattern upload player={} moved={}",
                            player.getName(), retry);
                }
            });
        }
    }
}
