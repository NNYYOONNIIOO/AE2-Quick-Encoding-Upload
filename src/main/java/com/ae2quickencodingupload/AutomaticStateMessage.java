package com.ae2quickencodingupload;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public final class AutomaticStateMessage implements IMessage {
    private boolean enabled;

    public AutomaticStateMessage() {
    }

    public AutomaticStateMessage(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        enabled = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(enabled);
    }
}
