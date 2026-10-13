/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.save.MessageSnapshots;
import static org.junit.jupiter.api.Assertions.*;

class MessageSnapshotsTest {
    @Test void everyMessageAndBoxKindSurvivesRoundTripsWithDetachedReferences() throws Exception {
        var messages = Arrays.stream(SystemMessageState.MessageType.values()).map(type -> new MessageState.Message(type, "Text " + type + " é", true, 2.25)).toList();
        var boxes = List.of(MessageState.Box.closed(), new MessageState.Box(MessageState.BoxMode.TEXT, "Notice", -1, null),
                new MessageState.Box(MessageState.BoxMode.SYSTEM, "Selected", 2, null),
                new MessageState.Box(MessageState.BoxMode.FOCUS, "Expired", -1, messages.getFirst()));
        for (var box : boxes) {
            var state = new MessageState(false, messages, box);var bytes = MessageSnapshots.encode(state);
            for (int i = 0; i < 5; i++) {var restored = MessageSnapshots.decode(bytes);assertEquals(state, restored);assertArrayEquals(bytes, MessageSnapshots.encode(restored));}
            for (int i = 0; i < bytes.length; i++) {var truncated = Arrays.copyOf(bytes, i);assertThrows(IOException.class, () -> MessageSnapshots.decode(truncated));}
            assertThrows(IOException.class, () -> MessageSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        }
    }
    @Test void malformedHeadersFlagsCountsTagsReferencesAndSizesFail() throws Exception {
        var message = new MessageState.Message(SystemMessageState.MessageType.INFO, "hello", false, 59);
        var bytes = MessageSnapshots.encode(new MessageState(true, List.of(message), MessageState.Box.closed()));
        for (int offset : new int[]{0, 4, 9, 13}) {var bad = bytes.clone();ByteBuffer.wrap(bad).putInt(offset, Integer.MAX_VALUE);assertThrows(IOException.class, () -> MessageSnapshots.decode(bad));}
        var flag = bytes.clone();flag[8] = 3;assertThrows(IOException.class, () -> MessageSnapshots.decode(flag));
        var tag = bytes.clone();tag[17] = 'X';assertThrows(IOException.class, () -> MessageSnapshots.decode(tag));
        assertThrows(IllegalArgumentException.class, () -> new MessageState(true, List.of(), new MessageState.Box(MessageState.BoxMode.SYSTEM, "bad", 0, null)));
        assertThrows(IllegalArgumentException.class, () -> new MessageState.Box(MessageState.BoxMode.SYSTEM, "bad", 0, message));
        assertThrows(IllegalArgumentException.class, () -> new MessageState.Box(MessageState.BoxMode.CLOSED, "bad", -1, null));
        assertThrows(IllegalArgumentException.class, () -> new MessageState(true, Collections.nCopies(MessageState.MAX_MESSAGES + 1, message), MessageState.Box.closed()));
        var large = new MessageState.Message(message.type(), "a".repeat(60_000), false, 0);
        assertThrows(IOException.class, () -> MessageSnapshots.encode(new MessageState(true, Collections.nCopies(20, large), MessageState.Box.closed())));
        assertThrows(IOException.class, () -> MessageSnapshots.decode(new byte[MessageSnapshots.MAX_BYTES + 1]));
    }
}
