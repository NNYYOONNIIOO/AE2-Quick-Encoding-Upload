package com.ae2quickencodingupload;

import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public final class UploadActionMessage implements IMessage {
    private static final Logger LOGGER = LogManager.getLogger("ae2_quick_encoding_upload");

    public static final byte UPLOAD = 0;
    public static final byte TOGGLE_AUTOMATIC = 1;
    public static final byte REQUEST_AUTOMATIC = 2;
    public static final byte SYNC_AUTOMATIC = 3;

    private byte action;
    private boolean enabled;

    public UploadActionMessage() {
    }

    public UploadActionMessage(byte action, boolean enabled) {
        this.action = action;
        this.enabled = enabled;
    }

    public byte getAction() {
        return action;
    }

    public boolean isEnabled() {
        return enabled;
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
            com.ae2quickencodingupload.DebugLogger.info(LOGGER, "Received upload action {} from {}", message.action, player.getName());
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (message.action == REQUEST_AUTOMATIC) {
                        UploadNetwork.sendAutomaticStateTo(player,
                                AutoUploadState.isEnabled(player));
                    } else if (message.action == TOGGLE_AUTOMATIC) {
                        AutoUploadState.setEnabled(player, message.enabled);
                        UploadNetwork.sendAutomaticStateTo(player, message.enabled);
                        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "Automatic pattern upload for {} is now {}.",
                                player.getName(), message.enabled ? "enabled" : "disabled");
                    } else if (message.action == UPLOAD) {
                        int moved = PatternUploadService.uploadInventory(player);
                        com.ae2quickencodingupload.DebugLogger.info(LOGGER, "Manual pattern upload for {} moved {} pattern items.",
                                player.getName(), moved);
                    }
                }
            });
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    public static final class ClientHandler implements IMessageHandler<UploadActionMessage, IMessage> {
        @Override
        public IMessage onMessage(final UploadActionMessage message, MessageContext context) {
            if (message.action == SYNC_AUTOMATIC) {
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        AutoUploadSettings.setEnabled(message.enabled);
                    }
                });
            }
            return null;
        }
    }
}
