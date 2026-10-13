/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKSP v1: last-observed spatial membership and within-tick sensory results. */
public final class PositionSnapshots {
    private static final int MAGIC = 0x4d4b5350;
    private static final int MAX_ENTITIES = 1_000_000, MAX_SENSORS = 65_536, MAX_SENSED = 1_000_000;
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    public record Location(int x, int y, boolean obstacle) {
        public Location {if (x < 0 || y < 0) throw new IllegalArgumentException("Negative observed location");}
    }
    public record State(int width, int height, Map<EntityId, Location> observed, Map<EntityId, List<EntityId>> sensed) {
        public State {
            if (width < 1 || height < 1 || width > 65_535 || height > 65_535 || (long)width * height > MAX_ENTITIES)
                throw new IllegalArgumentException("Invalid spatial dimensions");
            Objects.requireNonNull(observed);Objects.requireNonNull(sensed);
            if (observed.size() > MAX_ENTITIES || sensed.size() > MAX_SENSORS) throw new IllegalArgumentException("Spatial state too large");
            var positions = new TreeMap<EntityId, Location>();
            for (var entry : observed.entrySet()) {
                validId(entry.getKey());var location = Objects.requireNonNull(entry.getValue());
                if (location.x() >= width || location.y() >= height) throw new IllegalArgumentException("Observed location outside map");
                positions.put(entry.getKey(), location);
            }
            var sensors = new TreeMap<EntityId, List<EntityId>>();long total = 0;
            for (var entry : sensed.entrySet()) {
                validId(entry.getKey());var values = entry.getValue();
                total += values.size();if (total > MAX_SENSED) throw new IllegalArgumentException("Too many sensed references");
                long previous = -1;
                for (var id : values) {
                    validId(id);if (id.getId() <= previous) throw new IllegalArgumentException("Invalid sensed order");previous = id.getId();
                }
                sensors.put(entry.getKey(), List.copyOf(values));
            }
            observed = Collections.unmodifiableMap(positions);sensed = Collections.unmodifiableMap(sensors);
        }
    }
    private static void validId(EntityId id) {
        if (id == null || id.getId() < 0) throw new IllegalArgumentException("Invalid spatial reference");
    }

    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(1);out.writeInt(state.width());out.writeInt(state.height());
            out.writeInt(state.observed().size());
            for (var entry : state.observed().entrySet()) {
                ENTITY_ID.write(out, entry.getKey());out.writeInt(entry.getValue().x());out.writeInt(entry.getValue().y());out.writeBoolean(entry.getValue().obstacle());
            }
            out.writeInt(state.sensed().size());
            for (var entry : state.sensed().entrySet()) {
                ENTITY_ID.write(out, entry.getKey());out.writeInt(entry.getValue().size());
                for (var id : entry.getValue()) ENTITY_ID.write(out, id);
            }
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Unsupported spatial section");
            int width = in.readInt(), height = in.readInt(), size = count(in.readInt(), MAX_ENTITIES);
            var positions = new TreeMap<EntityId, Location>();
            for (int i = 0; i < size; i++) {
                var id = ENTITY_ID.read(in);var location = new Location(in.readInt(), in.readInt(), BOOLEAN.read(in));
                if (positions.putIfAbsent(id, location) != null) throw new IOException("Duplicate observed location");
            }
            int sensors = count(in.readInt(), MAX_SENSORS), total = 0;
            var sensed = new TreeMap<EntityId, List<EntityId>>();
            for (int i = 0; i < sensors; i++) {
                var id = ENTITY_ID.read(in);int length = count(in.readInt(), MAX_SENSED - total);total += length;
                var values = new ArrayList<EntityId>(length);
                for (int j = 0; j < length; j++) values.add(ENTITY_ID.read(in));
                if (sensed.putIfAbsent(id, values) != null) throw new IOException("Duplicate sensor");
            }
            if (in.read() != -1) throw new IOException("Trailing spatial data");
            return new State(width, height, positions, sensed);
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid spatial section", e);}
    }
    private PositionSnapshots() { }
}
