package com.ae2quickencodingupload;

import org.apache.logging.log4j.Logger;

/** Logging helpers for verbose diagnostics controlled by UploadConfig. */
public final class DebugLogger {
    private DebugLogger() {
    }

    public static void info(Logger logger, String message, Object... arguments) {
        if (UploadConfig.isDebugLogging()) {
            logger.info(message, arguments);
        }
    }

    public static void debug(Logger logger, String message, Object... arguments) {
        if (UploadConfig.isDebugLogging()) {
            logger.debug(message, arguments);
        }
    }
}

