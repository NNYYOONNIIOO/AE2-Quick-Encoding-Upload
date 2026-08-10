package com.ae2quickencodingupload;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;

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
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            CHANNEL.registerMessage(AutomaticStateMessageHandler.class,
                    UploadActionMessage.class, 1, Side.CLIENT);
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
        CHANNEL.sendTo(new UploadActionMessage(
                UploadActionMessage.SYNC_AUTOMATIC, enabled), player);
    }
}
