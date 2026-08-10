package com.ae2quickencodingupload;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

public final class AutoUploadState {
    private static final String STATE_KEY = "ae2_quick_encoding_upload";
    private static final String ENABLED_KEY = "automatic_upload";

    private AutoUploadState() {
    }

    public static boolean isEnabled(EntityPlayerMP player) {
        if (player == null) {
            return false;
        }
        NBTTagCompound persisted = getPersistedState(player);
        return persisted != null && persisted.getBoolean(ENABLED_KEY);
    }

    public static void setEnabled(EntityPlayerMP player, boolean enabled) {
        if (player == null) {
            return;
        }

        NBTTagCompound entityData = player.getEntityData();
        NBTTagCompound persisted = entityData.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound state = persisted.getCompoundTag(STATE_KEY);
        state.setBoolean(ENABLED_KEY, enabled);
        persisted.setTag(STATE_KEY, state);
        entityData.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }

    private static NBTTagCompound getPersistedState(EntityPlayerMP player) {
        NBTTagCompound entityData = player.getEntityData();
        if (!entityData.hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10)) {
            return null;
        }
        NBTTagCompound persisted = entityData.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(STATE_KEY, 10)) {
            return null;
        }
        return persisted.getCompoundTag(STATE_KEY);
    }
}
