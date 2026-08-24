package com.ae2quickencodingupload;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;

public final class UploadNetwork {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE
            .newSimpleChannel(AE2QuickEncodingUpload.MODID);
    private static final int UPLOAD_ACTION_MESSAGE_ID = 0;
    private static final int AUTOMATIC_STATE_MESSAGE_ID = 1;
    private static boolean initialized;

    private UploadNetwork() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        CHANNEL.registerMessage(UploadActionMessage.Handler.class,
                UploadActionMessage.class, UPLOAD_ACTION_MESSAGE_ID, Side.SERVER);
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            CHANNEL.registerMessage(AutomaticStateMessageHandler.class,
                    AutomaticStateMessage.class, AUTOMATIC_STATE_MESSAGE_ID, Side.CLIENT);
        } else {
            CHANNEL.registerMessage(AutomaticStateMessageServerHandler.class,
                    AutomaticStateMessage.class, AUTOMATIC_STATE_MESSAGE_ID, Side.SERVER);
        }
        initialized = true;
    }

    public static void sendUpload() {
        CHANNEL.sendToServer(new UploadActionMessage(UploadActionMessage.UPLOAD, false));
    }

    public static void sendAutomaticState(boolean enabled) {
        CHANNEL.sendToServer(new UploadActionMessage(
                UploadActionMessage.TOGGLE_AUTOMATIC, enabled));
    }

    public static void requestAutomaticState() {
        CHANNEL.sendToServer(new UploadActionMessage(
                UploadActionMessage.REQUEST_AUTOMATIC, false));
    }

    public static void sendAutomaticStateTo(EntityPlayerMP player, boolean enabled) {
        CHANNEL.sendTo(new AutomaticStateMessage(enabled), player);
    }
}
