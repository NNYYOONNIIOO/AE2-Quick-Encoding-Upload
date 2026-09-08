package com.ae2quickencodingupload;

import java.io.File;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.config.Configuration;

public final class UploadConfig {
    private static final String CATEGORY_GENERAL = "general";
    private static final String DEBUG_LOGGING = "debug_logging";
    private static final Logger LOGGER = LogManager.getLogger(AE2QuickEncodingUpload.MODID);
    private static boolean debugLogging;

    private UploadConfig() {
    }

    public static synchronized void init(File configFile) {
        if (configFile == null) {
            return;
        }

        Configuration configuration = new Configuration(configFile);
        configuration.load();
        debugLogging = configuration.getBoolean(
                DEBUG_LOGGING,
                CATEGORY_GENERAL,
                false,
                "Enable verbose diagnostic logging for AE2 Quick Encoding Upload."
        );
        if (configuration.hasChanged()) {
            configuration.save();
        }
        configureLogger();
    }

    private static void configureLogger() {
        if (LOGGER instanceof org.apache.logging.log4j.core.Logger) {
            org.apache.logging.log4j.core.Logger coreLogger =
                    (org.apache.logging.log4j.core.Logger) LOGGER;
            coreLogger.setLevel(debugLogging ? Level.DEBUG : Level.WARN);
        }
    }

    public static boolean isDebugLogging() {
        return debugLogging;
    }
}
