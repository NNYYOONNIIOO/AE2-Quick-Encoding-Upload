package com.ae2quickencodingupload;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public final class UploadActionMessage implements IMessage {
    public static final byte UPLOAD = 0;
    public static final byte TOGGLE_AUTOMATIC = 1;

    private byte action;
    private boolean enabled;

    public UploadActionMessage() {
    }

    public UploadActionMessage(byte action, boolean enabled) {
        this.action = action;
        this.enabled = enabled;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        action = buffer.readByte();
        enabled = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(action);
        buffer.writeBoolean(enabled);
    }

    public static final class Handler implements IMessageHandler<UploadActionMessage, IMessage> {
        @Override
        public IMessage onMessage(final UploadActionMessage message, MessageContext context) {
            if (context == null || context.getServerHandler() == null) {
                return null;
            }
            final EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (message.action == TOGGLE_AUTOMATIC) {
                        AutoUploadState.setEnabled(player, message.enabled);
                    } else if (message.action == UPLOAD) {
                        PatternUploadService.uploadInventory(player);
                    }
                }
            });
            return null;
        }
    }
}
