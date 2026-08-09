package com.ae2quickencodingupload;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Server-side fallback which notices a newly created pattern after the encoder updates the inventory. */
public final class UploadServerHandler {
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END
                || event.player == null || event.player.world.isRemote
                || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (AutoUploadState.isEnabled(player)) {
            PatternUploadService.uploadInventory(player);
        }
    }
}
