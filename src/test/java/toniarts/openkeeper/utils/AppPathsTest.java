/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AppPathsTest {
    @TempDir Path temporary;

    @Test void macUsesApplicationSupportWithSpaces() {
        assertEquals(temporary.resolve("Library/Application Support/MacKeeper"),
                AppPaths.defaultDirectory("Mac OS X", temporary, null));
    }

    @Test void windowsUsesUserAppData() {
        assertEquals(temporary.resolve("Roaming/MacKeeper"),
                AppPaths.defaultDirectory("Windows 11", temporary, temporary.resolve("Roaming").toString()));
    }

    @Test void otherPlatformsRemainInsideTheUserDirectory() {
        assertEquals(temporary.resolve(".local/share/MacKeeper"),
                AppPaths.defaultDirectory("Linux", temporary, null));
    }

    @Test void failedWritePreservesLastGoodSettingsAndCleansTemporaryFile() throws Exception {
        Path settings = temporary.resolve("settings.properties");
        Files.writeString(settings, "last good settings");
        assertThrows(IOException.class, () -> AppPaths.writeAtomically(settings, stream -> {
            stream.write("partial".getBytes());
            throw new IOException("disk full");
        }));
        assertEquals("last good settings", Files.readString(settings));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }

    @Test void createsParentDirectoriesAndReplacesPreviousSettings() throws Exception {
        Path settings = temporary.resolve("new/settings.properties");
        AppPaths.writeAtomically(settings, output -> output.write("first".getBytes()));
        AppPaths.writeAtomically(settings, output -> output.write("second".getBytes()));
        assertEquals("second", Files.readString(settings));
    }
}
