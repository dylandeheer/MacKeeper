/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import com.jme3.system.AppSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingUtilsTest {
    @TempDir Path temporary;

    @Test void changedInstallationInvalidatesConvertedDataButSamePathRetainsIt() {
        AppSettings settings = new AppSettings(false);
        SettingUtils.updateDungeonKeeperFolder(settings, temporary.resolve("first").toString());
        settings.putInteger("TexturesVersion", 42);
        SettingUtils.updateDungeonKeeperFolder(settings, temporary.resolve("first/.").toString());
        assertEquals(42, settings.getInteger("TexturesVersion"));
        SettingUtils.updateDungeonKeeperFolder(settings, temporary.resolve("second").toString());
        assertFalse(settings.containsKey("TexturesVersion"));
        assertEquals(temporary.resolve("second") + java.io.File.separator,
                settings.getString("DungeonKeeperIIFolder"));
    }
}
