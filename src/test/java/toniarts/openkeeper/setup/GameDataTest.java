/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GameDataTest {
    @TempDir Path temporary;

    private void installation(Path root) throws Exception {
        for (String name : java.util.List.of("Data/editor/maps/FrontEnd3DLevel.kwd", "Data/Meshes.WAD",
                "Data/EngineTextures.WAD", "Data/Sprite.WAD", "Data/Paths.WAD", "Data/FrontEnd.WAD",
                "DK2TextureCache/EngineTextures.dat", "DK2TextureCache/EngineTextures.dir",
                "Data/Text/Default/Text.str", "Data/Text/Default/MBToUni.dat",
                "Data/Text/Default/FONT_FRONTEND20.bf4", "Data/Text/Default/FONT_INGAME10.bf4")) {
            Path file = root.resolve(name.toLowerCase(Locale.ROOT));
            Files.createDirectories(file.getParent());
            Files.writeString(file, "synthetic presence fixture, not game content");
        }
    }

    @Test void checksAllRequiredFilesWithoutEngineInitialization() {
        assertEquals(GameData.REQUIRED_FILES, GameData.missingFiles(temporary));
    }

    @Test void acceptsMixedCaseGameFilesInPathsWithSpaces() throws Exception {
        Path root = temporary.resolve("Dungeon Keeper 2");
        installation(root);
        assertTrue(GameData.missingFiles(root).isEmpty());
    }

    @Test void reportsIncompleteDownloadAndDoesNotModifyIt() throws Exception {
        installation(temporary);
        Path missing = temporary.resolve("data/meshes.wad");
        Files.delete(missing);
        assertEquals(java.util.List.of("Data/Meshes.WAD"), GameData.missingFiles(temporary));
        assertFalse(Files.exists(missing));
    }

    @Test void directoryIsNotAcceptedInPlaceOfAnArchive() throws Exception {
        installation(temporary);
        Path archive = temporary.resolve("data/meshes.wad");
        Files.delete(archive);
        Files.createDirectory(archive);
        assertEquals(java.util.List.of("Data/Meshes.WAD"), GameData.missingFiles(temporary));
    }

    @Test void detectsSteamConsoleDepotAndIgnoresIncompleteDownloads() throws Exception {
        Path root = temporary.resolve("steamapps/content/app_2616460/depot_2616461");
        Files.createDirectories(temporary.resolve("steamapps/common/Incomplete"));
        installation(root);
        assertEquals(root, GameData.discoverSteamData(temporary).orElseThrow());
    }

    @Test void missingLibraryReturnsNoCandidate() {
        assertTrue(GameData.discoverSteamData(temporary).isEmpty());
    }

    @Test void missingFrontendArchiveAndLanguageDataAreReported() throws Exception {
        installation(temporary);
        Files.delete(temporary.resolve("data/frontend.wad"));
        Files.delete(temporary.resolve("data/text/default/text.str"));
        assertEquals(java.util.List.of("Data/FrontEnd.WAD", "Data/Text/Default/Text.str"),
                GameData.missingFiles(temporary));
    }
}
