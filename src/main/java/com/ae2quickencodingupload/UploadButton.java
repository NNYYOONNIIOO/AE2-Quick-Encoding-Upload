package com.ae2quickencodingupload;

import appeng.client.gui.widgets.ITooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.translation.I18n;

public final class UploadButton extends GuiButton implements ITooltip {
    private static final ResourceLocation AE2_BUTTON_BACKGROUND = new ResourceLocation(
            "ae2_quick_encoding", "textures/guis/set.png");
    private static final ResourceLocation MANUAL = new ResourceLocation(
            AE2QuickEncodingUpload.MODID, "textures/guis/upload.png");
    private static final ResourceLocation ENABLED = new ResourceLocation(
            AE2QuickEncodingUpload.MODID, "textures/guis/upload_true.png");
    private static final ResourceLocation DISABLED = new ResourceLocation(
            AE2QuickEncodingUpload.MODID, "textures/guis/upload_false.png");

    private boolean settingsMode;
    private boolean automaticEnabled;

    public UploadButton(int x, int y) {
        super(0xAE2001, x, y, 16, 16, "");
    }

    public void setState(boolean settingsMode, boolean automaticEnabled) {
        this.settingsMode = settingsMode;
        this.automaticEnabled = automaticEnabled;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
        if (!visible) {
            return;
        }
        ResourceLocation icon = settingsMode
                ? (automaticEnabled ? ENABLED : DISABLED) : MANUAL;
        minecraft.getTextureManager().bindTexture(AE2_BUTTON_BACKGROUND);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, 16, 16, 16, 16);
        minecraft.getTextureManager().bindTexture(icon);
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, 16, 16, 16, 16);
    }

    @Override
    public String getMessage() {
        if (!settingsMode) {
            return I18n.translateToLocal("ae2_quick_encoding_upload.gui.upload");
        }
        return I18n.translateToLocal(automaticEnabled
                ? "ae2_quick_encoding_upload.gui.auto_upload.enabled"
                : "ae2_quick_encoding_upload.gui.auto_upload.disabled");
    }

    @Override public int xPos() { return x; }
    @Override public int yPos() { return y; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public boolean isVisible() { return visible; }
}
