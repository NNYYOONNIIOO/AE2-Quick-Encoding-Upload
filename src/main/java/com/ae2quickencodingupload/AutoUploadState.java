package com.ae2quickencodingupload;

import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AutoUploadState {
    private static final Map<UUID, Boolean> ENABLED = new ConcurrentHashMap<>();

    private AutoUploadState() {
    }

    public static boolean isEnabled(EntityPlayerMP player) {
        if (player == null) {
            return false;
        }
        Boolean value = ENABLED.get(player.getUniqueID());
        return value != null && value;
    }

    public static void setEnabled(EntityPlayerMP player, boolean enabled) {
        if (player != null) {
            ENABLED.put(player.getUniqueID(), enabled);
        }
    }
}
