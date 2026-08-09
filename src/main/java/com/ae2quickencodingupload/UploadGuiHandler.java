package com.ae2quickencodingupload;

import com.ae2quickencoding.client.ClientHandler;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Forge-event fallback for click dispatch, including Quick Encoding settings mode. */
@SideOnly(Side.CLIENT)
public final class UploadGuiHandler {
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event != null && UploadClientActions.handle(event.getGui(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event != null) {
            UploadClientActions.ensure(event.getGui(), event.getButtonList());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onDrawPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (event != null) {
            UploadClientActions.ensure(event.getGui(), null);
        }
    }
}
