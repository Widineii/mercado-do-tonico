package br.com.mercadotonico.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

public final class SupportLogger {
    private static final Path LOG_FILE = Path.of("data", "logs", "app.log");

    private SupportLogger() {
    }

    public static void log(String level, String context, String message, String details) {
        try {
            Files.createDirectories(LOG_FILE.getParent());
            String line = LocalDateTime.now() + " [" + level + "] [" + context + "] " + message
                    + (details == null || details.isBlank() ? "" : " | " + details) + System.lineSeparator();
            Files.writeString(LOG_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging should not stop the app.
        }
    }

    public static void logException(String context, Throwable error, String details) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        log("ERROR", context, error.getClass().getSimpleName(),
                (details == null || details.isBlank() ? "" : details + " | ") + trace);
    }
}
