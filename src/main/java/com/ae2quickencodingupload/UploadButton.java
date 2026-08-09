package com.ae2quickencodingupload;

import appeng.client.gui.widgets.ITooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.translation.I18n;

/** The upload control rendered on top of the pattern terminal GUI. */
public final class UploadButton extends GuiButton implements ITooltip {
    private static final int SIZE = 16;
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
        super(0xAE2001, x, y, SIZE, SIZE, "");
        this.enabled = true;
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
        hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        minecraft.getTextureManager().bindTexture(AE2_BUTTON_BACKGROUND);
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
        minecraft.getTextureManager().bindTexture(settingsMode
                ? (automaticEnabled ? ENABLED : DISABLED) : MANUAL);
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
        GlStateManager.popMatrix();
    }

    @Override
    public String getMessage() {
        return settingsMode
                ? I18n.translateToLocal("ae2_quick_encoding_upload.gui.auto_upload")
                : I18n.translateToLocal("ae2_quick_encoding_upload.gui.upload");
    }

    public String getTooltipState() {
        return I18n.translateToLocal(settingsMode
                ? (automaticEnabled
                        ? "ae2_quick_encoding_upload.gui.auto_upload.enabled"
                        : "ae2_quick_encoding_upload.gui.auto_upload.disabled")
                : "ae2_quick_encoding_upload.gui.upload");
    }

    @Override public int xPos() { return x; }
    @Override public int yPos() { return y; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public boolean isVisible() { return visible; }
}
