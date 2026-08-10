package com.ae2quickencodingupload;

/** Client-side mirror of the per-player automatic-upload state. */
public final class AutoUploadSettings {
    private static volatile boolean enabled;

    private AutoUploadSettings() {
    }

    public static synchronized void init() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static synchronized boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
    }
}
