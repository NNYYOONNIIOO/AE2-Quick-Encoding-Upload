package com.ae2quickencodingupload;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class AutomaticStateMessageHandler
        implements IMessageHandler<AutomaticStateMessage, IMessage> {
    @Override
    public IMessage onMessage(final AutomaticStateMessage message, MessageContext context) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                AutoUploadSettings.setEnabled(message.isEnabled());
            }
        });
        return null;
    }
}
