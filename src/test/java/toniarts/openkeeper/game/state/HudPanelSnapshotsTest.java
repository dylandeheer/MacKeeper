/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.save.HudPanelSnapshots;
import toniarts.openkeeper.view.HudPanelState;
import static org.junit.jupiter.api.Assertions.*;

class HudPanelSnapshotsTest {
    @Test void everyMenuDestinationAndNullableValueRoundTripsWithoutExecutableStrings() throws Exception {
        var menus = new ArrayList<HudPanelState.Menu>();menus.add(HudPanelState.Menu.main(false));
        menus.add(new HudPanelState.Menu(true, HudPanelState.Page.QUIT, null, null, null));
        for (var destination : HudPanelState.Destination.values()) menus.add(new HudPanelState.Menu(true, HudPanelState.Page.CONFIRMATION, HudPanelState.Page.QUIT, "Éxit", destination));
        for (var menu : menus) for (String caption : Arrays.asList(null, "Caption ${level.8}")) {
            var saved = new HudPanelState(.125f, caption, caption == null ? null : 8, List.of(9, 8, 9), false, true, 1, menu);
            byte[] bytes = HudPanelSnapshots.encode(saved);
            assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("quitTo"));
            for (int i = 0; i < 3; i++) assertEquals(saved, HudPanelSnapshots.decode(HudPanelSnapshots.encode(HudPanelSnapshots.decode(bytes))));
            for (int i = 0; i < bytes.length; i++) {byte[] bad = Arrays.copyOf(bytes, i);assertThrows(IOException.class, () -> HudPanelSnapshots.decode(bad));}
            assertThrows(IOException.class, () -> HudPanelSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        }
    }
    @Test void invalidEnumsCountsClockIdsAndBooleansAreRejected() throws Exception {
        var saved = new HudPanelState(0, null, null, List.of(), false, false, 0, HudPanelState.Menu.main(false));
        byte[] bytes = HudPanelSnapshots.encode(saved);
        for (int offset : new int[]{0, 4, 14}) {var bad = bytes.clone();ByteBuffer.wrap(bad).putInt(offset, Integer.MAX_VALUE);assertThrows(IOException.class, () -> HudPanelSnapshots.decode(bad));}
        var flag = bytes.clone();flag[12] = 3;assertThrows(IOException.class, () -> HudPanelSnapshots.decode(flag));
        var clock = bytes.clone();ByteBuffer.wrap(clock).putFloat(8, Float.NaN);assertThrows(IOException.class, () -> HudPanelSnapshots.decode(clock));
        for (float time : new float[]{-1, .25f, Float.POSITIVE_INFINITY}) assertThrows(IllegalArgumentException.class, () -> new HudPanelState(time, null, null, List.of(), false, false, 0, saved.menu()));
        assertThrows(IllegalArgumentException.class, () -> new HudPanelState(0, null, 32768, List.of(), false, false, 0, saved.menu()));
        assertThrows(IllegalArgumentException.class, () -> new HudPanelState(0, null, null, Collections.nCopies(4097, 1), false, false, 0, saved.menu()));
        assertThrows(IllegalArgumentException.class, () -> new HudPanelState.Menu(true, HudPanelState.Page.CONFIRMATION, HudPanelState.Page.CONFIRMATION, "Exit", HudPanelState.Destination.DESKTOP));
        assertThrows(IllegalArgumentException.class, () -> HudPanelState.Destination.fromAction("quitToOS(); somethingElse()"));
        assertThrows(IOException.class, () -> HudPanelSnapshots.decode(new byte[HudPanelSnapshots.MAX_BYTES + 1]));
    }
}
