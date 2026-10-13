/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Fingerprints of the exact bytes parsed, in load order. No original game bytes are retained. */
public record MapSource(String mapPath, List<File> files) {
    public record File(String path, long size, String sha256) {
        public File {
            path = portablePath(path);
            if (size < 0 || size > MAX_FILE_BYTES) throw new IllegalArgumentException("Invalid map source size");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source digest");
        }
    }
    public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_FILES = 256;
    public MapSource {
        mapPath = portablePath(mapPath);files = List.copyOf(files);
        if (files.isEmpty() || files.size() > MAX_FILES || !files.getFirst().path().equals(mapPath))
            throw new IllegalArgumentException("Invalid map source manifest");
    }
    /** Locate installed data without relying on the original machine's absolute paths or cached names. */
    public Path resolve(Path installedRoot) throws java.io.IOException {
        Path root = installedRoot.toRealPath();
        Path map = null;
        for (File source : files) {
            Path current = root;
            for (String part : source.path().split("/")) {
                Path match = null;
                try (var entries = java.nio.file.Files.newDirectoryStream(current)) {
                    for (Path entry : entries) if (entry.getFileName().toString().equalsIgnoreCase(part)) {
                        if (match != null) throw new java.io.IOException("Ambiguous installed data path: " + source.path());
                        match = entry;
                    }
                }
                if (match == null) throw new java.io.IOException("Missing installed data: " + source.path());
                current = match.toRealPath();
                if (!current.startsWith(root)) throw new java.io.IOException("Installed data escapes its directory: " + source.path());
            }
            if (!java.nio.file.Files.isRegularFile(current) || java.nio.file.Files.size(current) != source.size())
                throw new java.io.IOException("Installed data size differs: " + source.path());
            MessageDigest digest = sha256();
            long count = 0;
            try (var input = java.nio.file.Files.newInputStream(current)) {
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    count += length;
                    if (count > source.size()) throw new java.io.IOException("Installed data grew: " + source.path());
                    digest.update(buffer, 0, length);
                }
            }
            if (count != source.size() || !HexFormat.of().formatHex(digest.digest()).equals(source.sha256()))
                throw new java.io.IOException("Installed data content differs: " + source.path());
            if (map == null) map = current;
        }
        return map;
    }
    public static String portablePath(String value) {
        Objects.requireNonNull(value);
        String path = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.isBlank() || path.length() > 1024 || path.startsWith("/") || path.contains(":"))
            throw new IllegalArgumentException("Map source must be relative to the game data directory");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IllegalArgumentException("Invalid map source path");
        return path;
    }
    public static File fingerprint(Path root, Path file, byte[] bytes) throws java.io.IOException {
        return new File(root.toRealPath().relativize(file.toRealPath()).toString(), bytes.length, digest(bytes));
    }
    public static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }
    private static MessageDigest sha256() {
        try {return MessageDigest.getInstance("SHA-256");}
        catch (NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}
