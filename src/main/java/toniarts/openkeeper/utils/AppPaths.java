/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 MacKeeper contributors
 */
package toniarts.openkeeper.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Writable application data is independent of the installed bundle and working directory. */
public final class AppPaths {
    private AppPaths() { }

    public static Path dataDirectory() {
        String override = System.getProperty("mackeeper.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return defaultDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")),
                System.getenv("APPDATA"));
    }

    static Path defaultDirectory(String os, Path home, String appData) {
        String name = os.toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return home.resolve("Library/Application Support/MacKeeper");
        }
        if (name.startsWith("windows") && appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve("MacKeeper");
        }
        return home.resolve(".local/share/MacKeeper");
    }

    public static Path convertedAssets() { return dataDirectory().resolve("assets/Converted"); }
    public static Path screenshots() { return dataDirectory().resolve("Screenshots"); }

    public static void initialize() throws IOException {
        Files.createDirectories(dataDirectory());
        Files.createDirectories(screenshots());
        Files.createDirectories(dataDirectory().resolve("Logs"));
    }

    @FunctionalInterface
    public interface Writer { void write(OutputStream output) throws IOException; }

    /** Never truncate the last good settings file if serialization fails. */
    public static void writeAtomically(Path destination, Writer writer) throws IOException {
        Path target = destination.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".mackeeper-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                writer.write(output);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
