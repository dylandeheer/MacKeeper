/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.map.FlashTileControl;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class MapControlSnapshotsTest {
    @Test void strictCodecKeepsSignedRemainingTimeAndDetachedOrderedPoints() throws Exception {
        var points = new ArrayList<>(List.of(new Point(2, 3), new Point(1, 0), new Point(2, 3)));
        var state = new FlashTileControl.State(points, (short) 255, -.125f, false);points.clear();
        byte[] bytes = MapControlSnapshots.encode(List.of(state));var decoded = MapControlSnapshots.decode(bytes);
        assertEquals(List.of(state), decoded);assertEquals(state, FlashTileControl.restoreState(decoded.getFirst()).captureState());
        assertThrows(UnsupportedOperationException.class, () -> state.points().clear());
        for (int n = 0; n < bytes.length; n++) {
            byte[] cut = Arrays.copyOf(bytes, n);assertThrows(IOException.class, () -> MapControlSnapshots.decode(cut));
        }
        for (int offset : new int[]{0, 4, 8, 12}) {
            byte[] bad = bytes.clone();ByteBuffer.wrap(bad).putInt(offset, -1);assertThrows(IOException.class, () -> MapControlSnapshots.decode(bad));
        }
        byte[] invalidBool = bytes.clone();invalidBool[invalidBool.length - 1] = 2;
        assertThrows(IOException.class, () -> MapControlSnapshots.decode(invalidBool));
        byte[] nan = bytes.clone();ByteBuffer.wrap(nan).putFloat(nan.length - 5, Float.NaN);
        assertThrows(IOException.class, () -> MapControlSnapshots.decode(nan));
        assertThrows(IOException.class, () -> MapControlSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> new FlashTileControl.State(List.of(new Point(-1, 0)), (short) 3, 1, true));
    }
}
