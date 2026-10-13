/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.save.InteractionSnapshots;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState.Type;
import static org.junit.jupiter.api.Assertions.*;

class InteractionSnapshotsTest {
    @Test void allToolsAndEnabledStatesSurviveRepeatedRoundTrips() throws Exception {
        for (var type : Type.values()) for (boolean enabled : new boolean[]{false, true}) {
            int id = switch(type) {case NONE, SELL, STUFF_IN_HAND -> 0;default -> 1;};
            var saved = new InteractionSelection((short)3, enabled, type, id);byte[] bytes = InteractionSnapshots.encode(saved);
            for (int i = 0; i < 4; i++) {var decoded = InteractionSnapshots.decode(bytes);assertEquals(saved, decoded);assertArrayEquals(bytes, InteractionSnapshots.encode(decoded));}
            for (int i = 0; i < bytes.length; i++) {var bad = Arrays.copyOf(bytes, i);assertThrows(IOException.class, () -> InteractionSnapshots.decode(bad));}
            assertThrows(IOException.class, () -> InteractionSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        }
    }
    @Test void malformedHeadersPlayerFlagsTagsCountsAndItemIdsAreRejected() throws Exception {
        var bytes = InteractionSnapshots.encode(new InteractionSelection((short)3, false, Type.ROOM, 1));
        for (int offset : new int[]{0, 4, 11, bytes.length - 4}) {var bad = bytes.clone();ByteBuffer.wrap(bad).putInt(offset, Integer.MAX_VALUE);assertThrows(IOException.class, () -> InteractionSnapshots.decode(bad));}
        var player = bytes.clone();ByteBuffer.wrap(player).putShort(8, (short)-1);assertThrows(IOException.class, () -> InteractionSnapshots.decode(player));
        var flag = bytes.clone();flag[10] = 3;assertThrows(IOException.class, () -> InteractionSnapshots.decode(flag));
        var tag = bytes.clone();tag[15] = 'X';assertThrows(IOException.class, () -> InteractionSnapshots.decode(tag));
        var item = bytes.clone();ByteBuffer.wrap(item).putInt(item.length - 4, 0);assertThrows(IOException.class, () -> InteractionSnapshots.decode(item));
        assertThrows(IOException.class, () -> InteractionSnapshots.decode(new byte[InteractionSnapshots.MAX_BYTES + 1]));
    }
}
