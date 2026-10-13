/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SaveFilesTest {
    @TempDir Path profile;
    @Test void namesStaySingleLeavesAndNormalizeExtensionAndUnicode() {
        assertEquals("Café 1", SaveFiles.name("  Cafe\u0301 1.MKLS  "));
        assertEquals("a".repeat(64), SaveFiles.name("a".repeat(64)));
        for (String value : List.of("", "../save", "a/b", "a\\b", ".hidden", "_", "save.", "x .mkls", "a".repeat(65), "save\nname"))
            assertThrows(IllegalArgumentException.class, () -> SaveFiles.name(value), value);
    }
    @Test void listsManualAndRecoverySeparatelyNewestFirstWithoutFollowingLinks() throws Exception {
        var files = new SaveFiles(profile);
        assertTrue(files.list(false).isEmpty());
        Path root = Files.createDirectories(profile.resolve("Saves/Recovery")).getParent();
        Path first = Files.write(root.resolve("Older.mkls"), new byte[]{1});
        Path second = Files.write(root.resolve("Newer.MKLS"), new byte[]{2});
        Files.setLastModifiedTime(first, FileTime.from(Instant.ofEpochSecond(1)));
        Files.setLastModifiedTime(second, FileTime.from(Instant.ofEpochSecond(2)));
        Files.write(root.resolve("partial.tmp"), new byte[]{3});
        Files.createDirectory(root.resolve("directory.mkls"));
        Files.createSymbolicLink(root.resolve("linked.mkls"), first);
        Files.write(root.resolve("Recovery/checkpoint.mkls"), new byte[]{4});
        assertEquals(List.of("Newer", "Older"), files.list(false).stream().map(SaveFiles.Entry::name).toList());
        assertEquals(1, files.list(true).size());
        assertTrue(files.list(true).getFirst().recovery());
        assertThrows(IOException.class, () -> files.target("linked"));
        assertThrows(IOException.class, () -> files.target("directory"));
    }
    @Test void publicationCannotClobberExistingSaveAndReplacementIsExplicit() throws Exception {
        var files = new SaveFiles(profile);var saved = LocalSessionArchivesTest.fixture(false);
        files.write("Original", saved, false);Path path = files.target("original.MKLS");
        assertEquals("Original.mkls", path.getFileName().toString());
        byte[] before = Files.readAllBytes(path);
        var replacement = LocalSessionArchivesTest.fixture(true);
        assertThrows(FileAlreadyExistsException.class, () -> files.write("ORIGINAL", replacement, false));
        assertArrayEquals(before, Files.readAllBytes(path));
        files.write("original", replacement, true);
        assertArrayEquals(LocalSessionArchives.encode(replacement), Files.readAllBytes(path));
        assertEquals(1, files.list(false).size());
        try (var entries = Files.list(path.getParent())) { assertEquals(1, entries.count()); }
    }
}
