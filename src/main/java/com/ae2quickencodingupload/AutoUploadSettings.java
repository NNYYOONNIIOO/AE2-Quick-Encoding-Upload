package com.ae2quickencodingupload;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public final class AutoUploadSettings {
    private static final String CATEGORY = "general";
    private static final String KEY = "automaticUpload";
    private static volatile boolean enabled;
    private static Configuration configuration;

    private AutoUploadSettings() {
    }

    public static synchronized void init(File file) {
        if (configuration != null) {
            return;
        }
        configuration = new Configuration(file);
        configuration.load();
        enabled = configuration.getBoolean(KEY, CATEGORY, false,
                "Automatically upload newly encoded patterns to matching ME interfaces.");
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static synchronized boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        if (configuration != null) {
            configuration.get(CATEGORY, KEY, false).set(value);
            configuration.save();
        }
    }
}
