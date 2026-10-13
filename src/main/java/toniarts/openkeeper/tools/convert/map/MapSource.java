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
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch (NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}
