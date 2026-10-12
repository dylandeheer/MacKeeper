/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetPathTest {
    @TempDir Path temporary;

    @Test
    void linkedAssetRootRetainsTheWholeRelativeModelName() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("original-converted-assets/Models"));
        Files.writeString(source.resolve("Dungeon Heart.j3o"), "fixture");
        Path linked = temporary.resolve("linked");
        Files.createSymbolicLink(linked, source.getParent());
        assertEquals(Path.of("Models", "Dungeon Heart.j3o").toString(),
                PathUtils.getCanonicalRelativePath(linked + File.separator, "Models/Dungeon Heart.j3o"));
    }

    @Test
    void ordinaryRootRetainsTheCorrectFilenameCase() throws Exception {
        Path models = Files.createDirectories(temporary.resolve("Models"));
        Files.writeString(models.resolve("Dungeon Heart.j3o"), "fixture");
        assertEquals(Path.of("Models", "Dungeon Heart.j3o").toString(),
                PathUtils.getCanonicalRelativePath(temporary + File.separator, "models/dungeon heart.j3o"));
    }
}
