package com.ae2quickencodingupload;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Registers the client-to-server codec entry on a dedicated server without
 * loading any client-only classes. This message is sent only by the server.
 */
public final class AutomaticStateMessageServerHandler
        implements IMessageHandler<AutomaticStateMessage, IMessage> {
    @Override
    public IMessage onMessage(AutomaticStateMessage message, MessageContext context) {
        return null;
    }
}
