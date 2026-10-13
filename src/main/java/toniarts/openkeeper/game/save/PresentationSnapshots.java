/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.tools.convert.map.TriggerAction;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.state.session.PresentationEvent.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKPE v1: bounded pending runtime notifications with named tags and explicit payloads. */
public final class PresentationSnapshots {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static byte[] encode(PresentationQueue.State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new FilterOutputStream(bytes) {
            private int written;
            private void reserve(int length) throws IOException {
                if (length < 0 || length > MAX_BYTES - written) throw new IOException("Presentation section too large");
                written += length;
            }
            @Override public void write(int value) throws IOException {reserve(1);out.write(value);}
            @Override public void write(byte[] values, int offset, int length) throws IOException {reserve(length);out.write(values, offset, length);}
        })) {
            out.writeInt(0x4d4b5045);out.writeInt(1);out.writeInt(state.events().size());
            for (var event : state.events()) {
                enumeration(Kind.class).write(out, event.kind());
                switch (event) {
                    case ChangedTiles e -> {writeTiles(out, e.points());}
                    case RevealedTiles e -> {writeTiles(out, e.points());BOOLEAN.write(out, e.explore());SHORT.write(out, e.keeperId());}
                    case FogDisabled e -> {SHORT.write(out, e.keeperId());}
                    case FogReset e -> {SHORT.write(out, e.keeperId());}
                    case Gold e -> {SHORT.write(out, e.keeperId());LONG.write(out, e.gold());INT.write(out, e.reserve());}
                    case Mana e -> {SHORT.write(out, e.keeperId());INT.write(out, e.mana());INT.write(out, e.loose());INT.write(out, e.gain());}
                    case Built e -> {SHORT.write(out, e.keeperId());writeTiles(out, e.points());}
                    case Sold e -> {SHORT.write(out, e.keeperId());writeTiles(out, e.points());}
                    case Paused e -> {}
                    case Resumed e -> {}
                    case Widescreen e -> {BOOLEAN.write(out, e.enabled());}
                    case Ended e -> {BOOLEAN.write(out, e.win());DOUBLE.write(out, e.elapsedSeconds());}
                    case Speech e -> {INT.write(out, e.speechId());BOOLEAN.write(out, e.showText());BOOLEAN.write(out, e.introduction());INT.write(out, e.pathId());}
                    case Transition e -> {SHORT.write(out, e.pathId());VECTOR3.write(out, e.start().vector());}
                    case FlashButton e -> {nullable(enumeration(TriggerAction.MakeType.class)).write(out, e.buttonType());SHORT.write(out, e.targetId());nullable(enumeration(TriggerAction.ButtonType.class)).write(out, e.targetButtonType());BOOLEAN.write(out, e.enabled());INT.write(out, e.time());}
                    case Rotate e -> {VECTOR3.write(out, e.point().vector());BOOLEAN.write(out, e.relative());INT.write(out, e.angle());INT.write(out, e.time());}
                    case Message e -> {INT.write(out, e.textId());}
                    case Objective e -> {INT.write(out, e.objectiveId());}
                    case ZoomPoint e -> {VECTOR3.write(out, e.point().vector());}
                    case FlashedTiles e -> {writeTiles(out, e.points());BOOLEAN.write(out, e.enabled());SHORT.write(out, e.keeperId());}
                    case ZoomEntity e -> {ENTITY_ID.write(out, e.entityId());}
                    case Flower e -> {ENTITY_ID.write(out, e.entityId());INT.write(out, e.interval());}
                    case ResearchUpdate e -> {enumeration(ResearchAction.class).write(out, e.action());SHORT.write(out, e.keeperId());writeResearch(out, e.research());}
                    case Possession e -> {nullable(ENTITY_ID).write(out, e.target());}
                    case EnemySighted e -> {ENTITY_ID.write(out, e.entityId());}
                }
            }
        }
        return bytes.toByteArray();
    }
    public static PresentationQueue.State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b5045 || in.readInt() != 1) throw new IOException("Unsupported presentation section");
            int size = count(in.readInt(), PresentationQueue.MAX_EVENTS);
            var events = new ArrayList<PresentationEvent>();
            for (int i = 0; i < size; i++) events.add(switch (enumeration(Kind.class).read(in)) {
                case CHANGEDTILES -> new ChangedTiles(readTiles(in));
                case REVEALEDTILES -> new RevealedTiles(readTiles(in), BOOLEAN.read(in), SHORT.read(in));
                case FOGDISABLED -> new FogDisabled(SHORT.read(in));
                case FOGRESET -> new FogReset(SHORT.read(in));
                case GOLD -> new Gold(SHORT.read(in), LONG.read(in), INT.read(in));
                case MANA -> new Mana(SHORT.read(in), INT.read(in), INT.read(in), INT.read(in));
                case BUILT -> new Built(SHORT.read(in), readTiles(in));
                case SOLD -> new Sold(SHORT.read(in), readTiles(in));
                case PAUSED -> new Paused();
                case RESUMED -> new Resumed();
                case WIDESCREEN -> new Widescreen(BOOLEAN.read(in));
                case ENDED -> new Ended(BOOLEAN.read(in), DOUBLE.read(in));
                case SPEECH -> new Speech(INT.read(in), BOOLEAN.read(in), BOOLEAN.read(in), INT.read(in));
                case TRANSITION -> new Transition(SHORT.read(in), Vec3.of(VECTOR3.read(in)));
                case FLASHBUTTON -> new FlashButton(nullable(enumeration(TriggerAction.MakeType.class)).read(in), SHORT.read(in), nullable(enumeration(TriggerAction.ButtonType.class)).read(in), BOOLEAN.read(in), INT.read(in));
                case ROTATE -> new Rotate(Vec3.of(VECTOR3.read(in)), BOOLEAN.read(in), INT.read(in), INT.read(in));
                case MESSAGE -> new Message(INT.read(in));
                case OBJECTIVE -> new Objective(INT.read(in));
                case ZOOMPOINT -> new ZoomPoint(Vec3.of(VECTOR3.read(in)));
                case FLASHEDTILES -> new FlashedTiles(readTiles(in), BOOLEAN.read(in), SHORT.read(in));
                case ZOOMENTITY -> new ZoomEntity(ENTITY_ID.read(in));
                case FLOWER -> new Flower(ENTITY_ID.read(in), INT.read(in));
                case RESEARCHUPDATE -> new ResearchUpdate(enumeration(ResearchAction.class).read(in), SHORT.read(in), readResearch(in));
                case POSSESSION -> new Possession(nullable(ENTITY_ID).read(in));
                case ENEMYSIGHTED -> new EnemySighted(ENTITY_ID.read(in));
            });
            if (in.read() != -1) throw new IOException("Trailing presentation section data");
            return new PresentationQueue.State(events);
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid presentation section", failure);}
    }
    private static void writeTiles(DataOutput out, List<Point> points) throws IOException {
        out.writeInt(points.size());for (var point : points) POINT.write(out, point);
    }
    private static List<Point> readTiles(DataInputStream in) throws IOException {
        int size = count(in.readInt(), PresentationEvent.MAX_TILES);
        if (size > in.available() / 8) throw new IOException("Truncated presentation tiles");
        var points = new ArrayList<Point>(size);for (int i = 0; i < size; i++) points.add(POINT.read(in));return points;
    }
    private static void writeResearch(DataOutput out, KeeperState.Research value) throws IOException {
        out.writeShort(value.id());enumeration(ResearchableType.class).write(out, value.type());
        BOOLEAN.write(out, value.spell());BOOLEAN.write(out, value.discovered());out.writeInt(value.research());BOOLEAN.write(out, value.upgraded());
    }
    private static KeeperState.Research readResearch(DataInput in) throws IOException {
        return new KeeperState.Research(in.readShort(), enumeration(ResearchableType.class).read(in), BOOLEAN.read(in), BOOLEAN.read(in), in.readInt(), BOOLEAN.read(in));
    }
    private PresentationSnapshots() {}
}
