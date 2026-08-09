package com.ae2quickencodingupload;

import appeng.client.gui.widgets.ITooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.translation.I18n;

/** Upload button using AE2's native left-side button background and a transparent icon. */
public final class UploadButton extends GuiButton implements ITooltip {
    private static final int SIZE = 16;
    private static final ResourceLocation AE2_BUTTONS = new ResourceLocation(
            "appliedenergistics2", "textures/guis/states.png");
    private static final int AE2_TEXTURE_SIZE = 256;
    private static final int AE2_BUTTON_BACKGROUND_UV = AE2_TEXTURE_SIZE - SIZE;
    private static final ResourceLocation MANUAL = new ResourceLocation(
            AE2QuickEncodingUpload.MODID, "textures/guis/upload.png");
    private static final ResourceLocation ENABLED = new ResourceLocation(
            AE2QuickEncodingUpload.MODID, "textures/guis/upload_true.png");
    private static final ResourceLocation DISABLED = new ResourceLocation(
            AE2QuickEncodingUpload.MODID, "textures/guis/upload_false.png");

    private boolean settingsMode;
    private boolean automaticEnabled;
    private boolean directAction;

    public UploadButton(int x, int y) {
        super(0xAE2001, x, y, SIZE, SIZE, "");
        this.enabled = true;
    }

    public void setState(boolean settingsMode, boolean automaticEnabled) {
        this.settingsMode = settingsMode;
        this.automaticEnabled = automaticEnabled;
    }

    @Override
    public boolean mousePressed(Minecraft minecraft, int mouseX, int mouseY) {
        if (!super.mousePressed(minecraft, mouseX, mouseY)) {
            return false;
        }
        UploadClientActions.handleDirect(
                minecraft == null ? null : minecraft.currentScreen, this);
        return true;
    }

    void markDirectAction() {
        this.directAction = true;
    }

    boolean consumeDirectAction() {
        boolean handled = this.directAction;
        this.directAction = false;
        return handled;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) {
            return;
        }

        float brightness = this.enabled ? 1.0F : 0.5F;
        minecraft.getTextureManager().bindTexture(AE2_BUTTONS);
        GlStateManager.color(brightness, brightness, brightness, 1.0F);
        this.drawTexturedModalRect(this.x, this.y,
                AE2_BUTTON_BACKGROUND_UV, AE2_BUTTON_BACKGROUND_UV, SIZE, SIZE);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );
        minecraft.getTextureManager().bindTexture(this.settingsMode
                ? (this.automaticEnabled ? ENABLED : DISABLED) : MANUAL);
        Gui.drawModalRectWithCustomSizedTexture(this.x, this.y, 0, 0,
                SIZE, SIZE, SIZE, SIZE);
        GlStateManager.disableBlend();

        this.hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public String getMessage() {
        if (!this.settingsMode) {
            return I18n.translateToLocal("ae2_quick_encoding_upload.gui.upload");
        }
        return I18n.translateToLocal("ae2_quick_encoding_upload.gui.auto_upload") + "\n"
                + I18n.translateToLocal(this.automaticEnabled
                ? "ae2_quick_encoding_upload.gui.auto_upload.enabled"
                : "ae2_quick_encoding_upload.gui.auto_upload.disabled");
    }

    @Override public int xPos() { return this.x; }
    @Override public int yPos() { return this.y; }
    @Override public int getWidth() { return this.width; }
    @Override public int getHeight() { return this.height; }
    @Override public boolean isVisible() { return this.visible; }
}
