package com.ae2quickencodingupload;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class UploadNetwork {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE
            .newSimpleChannel(AE2QuickEncodingUpload.MODID);
    private static boolean initialized;

    private UploadNetwork() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        CHANNEL.registerMessage(UploadActionMessage.Handler.class,
                UploadActionMessage.class, 0, Side.SERVER);
        initialized = true;
    }

    public static void sendUpload() {
        CHANNEL.sendToServer(new UploadActionMessage(UploadActionMessage.UPLOAD, false));
    }

    public static void sendAutomaticState(boolean enabled) {
        CHANNEL.sendToServer(new UploadActionMessage(
                UploadActionMessage.TOGGLE_AUTOMATIC, enabled));
    }
}
