/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {
    @TempDir Path temporary;

    @Test void macSetupDoesNotUseGlfwFirstThread() {
        var command = Launcher.childCommand(false, true, null, List.of());
        assertFalse(command.contains("-XstartOnFirstThread"));
        assertFalse(command.contains("-Djava.awt.headless=true"));
        assertTrue(command.contains("-setup-only"));
    }

    @Test void macGameUsesFirstThreadAndHeadlessAwt() {
        var command = Launcher.childCommand(true, true, null, List.of());
        assertTrue(command.contains("-XstartOnFirstThread"));
        assertTrue(command.contains("-Djava.awt.headless=true"));
        assertTrue(command.contains("-setup-complete"));
    }

    @Test void forwardsPathsAndArgumentsAsSeparateTokensWithoutShellExpansion() {
        String folder = temporary.resolve("Game with spaces $(no-shell)").toString();
        var command = Launcher.childCommand(true, false, folder, List.of("-level", "Level 1"));
        assertTrue(command.contains("-Dmackeeper.dk2.dir=" + folder));
        assertEquals("Level 1", command.get(command.indexOf("-level") + 1));
        assertFalse(command.contains("-XstartOnFirstThread"));
    }

    @Test void incompleteDataFailsBeforeStartingAnyProcess() throws Exception {
        assertEquals(2, Launcher.run(new String[]{"--data-dir", temporary.toString(), "--check-assets"}));
    }

    @Test void missingDirectoryArgumentProducesUsageError() throws Exception {
        assertEquals(2, Launcher.run(new String[]{"--data-dir"}));
        assertEquals(2, Launcher.run(new String[]{"--data-dir", "--check-assets"}));
    }

    @Test void helpNeedsNeitherGameDataNorADisplay() throws Exception {
        assertEquals(0, Launcher.run(new String[]{"--help"}));
    }

    @Test void packagedGameUsesCompanionButSetupUsesBundledJava() {
        String previous = System.getProperty("jpackage.app-path");
        try {
            Path executable = temporary.resolve("Folder with spaces/MacKeeper.app/Contents/MacOS/MacKeeper");
            System.setProperty("jpackage.app-path", executable.toString());
            var command = Launcher.childCommand(true, true, null, List.of("-level", "Level 1"));
            assertEquals(executable.resolveSibling("MacKeeperGame").toString(), command.getFirst());
            assertEquals("", command.get(2));
            assertEquals(List.of("-level", "Level 1"), command.subList(3, command.size()));
            assertTrue(Launcher.childCommand(false, true, null, List.of()).contains("-setup-only"));
        } finally {
            if (previous == null) System.clearProperty("jpackage.app-path");
            else System.setProperty("jpackage.app-path", previous);
        }
    }
}
