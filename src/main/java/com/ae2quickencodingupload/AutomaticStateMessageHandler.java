package com.ae2quickencodingupload;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class AutomaticStateMessageHandler
        implements IMessageHandler<UploadActionMessage, IMessage> {
    @Override
    public IMessage onMessage(final UploadActionMessage message, MessageContext context) {
        if (message.getAction() == UploadActionMessage.SYNC_AUTOMATIC) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    AutoUploadSettings.setEnabled(message.isEnabled());
                }
            });
        }
        return null;
    }
}
