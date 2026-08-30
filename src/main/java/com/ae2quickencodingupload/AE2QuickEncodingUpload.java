package com.ae2quickencodingupload;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod(
        modid = AE2QuickEncodingUpload.MODID,
        name = AE2QuickEncodingUpload.NAME,
        version = AE2QuickEncodingUpload.VERSION,
        dependencies = "required-after:forge;required-after:appliedenergistics2;"
                + "required-after:jei;required-after:ae2_quick_encoding"
)
public final class AE2QuickEncodingUpload {
    public static final String MODID = "ae2_quick_encoding_upload";
    public static final String NAME = "AE2 Quick Encoding Upload";
    public static final String VERSION = "1.0.2";

    public AE2QuickEncodingUpload() {
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AutoUploadSettings.init();
        UploadNetwork.init();
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            MinecraftForge.EVENT_BUS.register(new UploadGuiHandler());
        }
    }
}
