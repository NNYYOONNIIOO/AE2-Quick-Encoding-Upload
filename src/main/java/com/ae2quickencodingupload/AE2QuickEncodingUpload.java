package com.ae2quickencodingupload;

import net.minecraftforge.fml.common.Mod;

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
    public static final String VERSION = "1.0.0";

    private AE2QuickEncodingUpload() {
    }
}
