/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.simsilica.es.EntityId;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.PresentationSnapshots;
import toniarts.openkeeper.tools.convert.map.TriggerAction.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.game.state.session.PresentationEvent.*;

class PresentationSnapshotsTest {
    private static List<PresentationEvent> examples() {
        var tiles = List.of(new Point(12, 14), new Point(2, 4));var point = new Vec3(1.25f, -2, 3.5f);
        var research = new KeeperState.Research((short) 1, ResearchableType.ROOM, false, true, 24, false);
        return List.of(new ChangedTiles(tiles), new RevealedTiles(tiles, true, (short) 3), new FogDisabled((short) 3),
                new FogReset((short) 3), new Gold((short) 3, 8_000_000_000L, 42), new Mana((short) 3, 400, 31, 14),
                new Built((short) 3, tiles), new Sold((short) 3, tiles), new Paused(), new Resumed(), new Widescreen(true),
                new Ended(true, 123.25), new Speech(27, true, false, 32), new Transition((short) 67, point),
                new FlashButton(MakeType.MISC_BUTTON, (short) 243, ButtonType.GOLD_VALUE, true, 15),
                new Rotate(point, true, 90, 15), new Message(77), new Objective(42), new ZoomPoint(point),
                new FlashedTiles(tiles, false, (short) 3), new ZoomEntity(new EntityId(5_000_000_000L)),
                new Flower(new EntityId(0), 12), new ResearchUpdate(ResearchAction.ADDED, (short) 3, research),
                new Possession(new EntityId(91)), new EnemySighted(new EntityId(14)),
                new ResearchUpdate(ResearchAction.REMOVED, (short) 3, research), new ResearchUpdate(ResearchAction.CHANGED, (short) 3, research),
                new Possession(null), new FlashButton(MakeType.ROOM, (short) 1, null, false, 0),
                new FlashButton(null, (short) 0, null, false, 0));
    }

    @Test void allRuntimeMethodsAndPayloadKindsSurviveRepeatedRoundTripsWithoutDelivery() throws Exception {
        var state = new PresentationQueue.State(examples());var bytes = PresentationSnapshots.encode(state);
        assertEquals(EnumSet.allOf(Kind.class), state.events().stream().map(PresentationEvent::kind).collect(Collectors.toSet()));
        var runtimeMethods = Arrays.stream(GameSessionListener.class.getMethods()).map(java.lang.reflect.Method::getName)
                .filter(name -> !Set.of("onGameDataLoaded", "onLoadComplete", "onLoadStatusUpdate", "onGameStarted", "onLoadFailed").contains(name))
                .collect(Collectors.toSet());runtimeMethods.add("onEnemySighted");
        for (int i = 0; i < 5; i++) {
            state = PresentationSnapshots.decode(bytes);assertEquals(examples(), state.events());
            assertArrayEquals(bytes, PresentationSnapshots.encode(state));
            var methods = new HashSet<String>();
            var sink = PresentationQueueTest.target((name, args) -> methods.add(name));
            var recaptured = new PresentationQueue();var adapter = new QueuedGameSessionListener(recaptured, sink);
            for (var event : state.events()) {event.deliver(sink);event.deliver(adapter);}
            assertEquals(runtimeMethods, methods);assertEquals(state, recaptured.capture());
        }
    }

    @Test void rejectsEveryTruncationTrailingDataUnknownKindsAndInvalidHeaders() throws Exception {
        byte[] encoded = PresentationSnapshots.encode(new PresentationQueue.State(examples()));
        for (int length = 0; length < encoded.length; length++) {
            var bytes = Arrays.copyOf(encoded, length);assertThrows(IOException.class, () -> PresentationSnapshots.decode(bytes));
        }
        assertThrows(IOException.class, () -> PresentationSnapshots.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        for (int offset : new int[]{0, 4, 8, 12}) {
            var bytes = encoded.clone();ByteBuffer.wrap(bytes).putInt(offset, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> PresentationSnapshots.decode(bytes));
        }
        var unknown = encoded.clone();unknown[16] = 'X';assertThrows(IOException.class, () -> PresentationSnapshots.decode(unknown));
    }

    @Test void rejectsInvalidBooleansPositionsPlayersReferencesResearchAndTileCounts() throws Exception {
        assertInvalid("WIDESCREEN", out -> out.writeByte(2));
        assertInvalid("FOGRESET", out -> out.writeShort(256));
        assertInvalid("ZOOMENTITY", out -> out.writeLong(-1));
        assertInvalid("ZOOMPOINT", out -> {out.writeFloat(Float.NaN);out.writeFloat(0);out.writeFloat(0);});
        assertInvalid("ENDED", out -> {out.writeBoolean(true);out.writeDouble(-1);});
        assertInvalid("CHANGEDTILES", out -> out.writeInt(PresentationEvent.MAX_TILES + 1));
        assertInvalid("CHANGEDTILES", out -> out.writeInt(PresentationEvent.MAX_TILES));
        assertInvalid("RESEARCHUPDATE", out -> {text(out,"ADDED");out.writeShort(3);out.writeShort(256);text(out,"ROOM");out.writeBoolean(false);out.writeBoolean(true);out.writeInt(0);out.writeBoolean(false);});
        assertInvalid("POSSESSION", out -> out.writeByte(3));
    }

    @Test void boundedStateAndCodecRejectExcessWithoutChangingOriginalQueue() throws Exception {
        var events = new ArrayList<PresentationEvent>();events.add(new Message(1));
        var state = new PresentationQueue.State(events);events.clear();assertEquals(1, state.events().size());
        assertThrows(UnsupportedOperationException.class, () -> state.events().clear());
        assertThrows(IllegalArgumentException.class, () -> new PresentationQueue.State(Collections.nCopies(PresentationQueue.MAX_EVENTS + 1, new Paused())));
        var large = new ChangedTiles(Collections.nCopies(PresentationEvent.MAX_TILES, new Point(0, 0)));
        assertThrows(IOException.class, () -> PresentationSnapshots.encode(new PresentationQueue.State(Collections.nCopies(5, large))));
        assertThrows(IOException.class, () -> PresentationSnapshots.decode(new byte[PresentationSnapshots.MAX_BYTES + 1]));
    }

    interface Payload {void write(DataOutputStream out) throws IOException;}
    private static void assertInvalid(String kind, Payload payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {out.writeInt(0x4d4b5045);out.writeInt(1);out.writeInt(1);text(out, kind);payload.write(out);}
        assertThrows(IOException.class, () -> PresentationSnapshots.decode(bytes.toByteArray()));
    }
    private static void text(DataOutputStream out, String text) throws IOException {var bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
}
