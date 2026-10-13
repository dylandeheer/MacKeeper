/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.badlogic.gdx.math.Vector2;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import toniarts.openkeeper.utils.Point;

/** Bounded value codecs shared by the explicit save schemas. No class loading from input. */
final class SaveValues {
    static final int MAX_STRING_BYTES = 65_536;
    static final int MAX_LIST_ITEMS = 65_536;
    static final int MAX_MAP_ITEMS = 256;

    interface Value<T> {
        T copy(T value) throws IOException;
        void write(DataOutput out, T value) throws IOException;
        T read(DataInput in) throws IOException;
        default void references(T value, Set<EntityId> ids) { }
    }

    @FunctionalInterface private interface Writer<T> { void write(DataOutput out, T value) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInput in) throws IOException; }
    @FunctionalInterface private interface Copier<T> { T copy(T value) throws IOException; }

    private static <T> Value<T> scalar(Writer<T> writer, Reader<T> reader, Copier<T> copier) {
        return new Value<>() {
            public T copy(T value) throws IOException {
                if (value == null) throw new IOException("Missing required save value");
                return copier.copy(value);
            }
            public void write(DataOutput out, T value) throws IOException { writer.write(out, copy(value)); }
            public T read(DataInput in) throws IOException { return copy(reader.read(in)); }
        };
    }

    static final Value<Integer> INT = scalar(DataOutput::writeInt, DataInput::readInt, v -> v);
    static final Value<Long> LONG = scalar(DataOutput::writeLong, DataInput::readLong, v -> v);
    static final Value<Short> SHORT = scalar((out, v) -> out.writeShort(v), DataInput::readShort, v -> v);
    static final Value<Boolean> BOOLEAN = scalar(DataOutput::writeBoolean, in -> {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid boolean in save");
        return value == 1;
    }, v -> v);
    static final Value<Float> FLOAT = scalar(DataOutput::writeFloat, DataInput::readFloat, v -> {
        if (!Float.isFinite(v)) throw new IOException("Non-finite float in save");
        return v;
    });
    static final Value<Double> DOUBLE = scalar(DataOutput::writeDouble, DataInput::readDouble, v -> {
        if (!Double.isFinite(v)) throw new IOException("Non-finite double in save");
        return v;
    });
    static final Value<String> TEXT = scalar((out, v) -> {
        byte[] bytes = utf8(v);
        out.writeInt(bytes.length);out.write(bytes);
    }, in -> {
        int length = count(in.readInt(), MAX_STRING_BYTES);
        byte[] bytes = new byte[length];in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }, v -> { utf8(v);return v; });

    private static byte[] utf8(String value) throws IOException {
        // Bound UTF-16 length before allocating the encoded form, and reject
        // unpaired surrogates instead of silently changing the creature name.
        if (value.length() > MAX_STRING_BYTES) throw new IOException("Save text is too long");
        var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
        count(buffer.remaining(), MAX_STRING_BYTES);
        byte[] bytes = new byte[buffer.remaining()];buffer.get(bytes);return bytes;
    }

    static final Value<Point> POINT = scalar((out, v) -> {out.writeInt(v.x);out.writeInt(v.y);},
            in -> new Point(in.readInt(), in.readInt()), v -> new Point(v.x, v.y));
    static final Value<Vector2> VECTOR2 = scalar((out, v) -> {FLOAT.write(out, v.x);FLOAT.write(out, v.y);},
            in -> new Vector2(FLOAT.read(in), FLOAT.read(in)), v -> new Vector2(FLOAT.copy(v.x), FLOAT.copy(v.y)));
    static final Value<Vector3f> VECTOR3 = scalar((out, v) -> {FLOAT.write(out, v.x);FLOAT.write(out, v.y);FLOAT.write(out, v.z);},
            in -> new Vector3f(FLOAT.read(in), FLOAT.read(in), FLOAT.read(in)),
            v -> new Vector3f(FLOAT.copy(v.x), FLOAT.copy(v.y), FLOAT.copy(v.z)));
    static final Value<EntityId> ENTITY_ID = new Value<>() {
        public EntityId copy(EntityId value) throws IOException {
            // Zay-ES allocates its first entity at zero.
            if (value == null || value.getId() < 0) throw new IOException("Invalid entity reference in save");
            return value; // EntityId is immutable.
        }
        public void write(DataOutput out, EntityId value) throws IOException {out.writeLong(copy(value).getId());}
        public EntityId read(DataInput in) throws IOException {return copy(new EntityId(in.readLong()));}
        public void references(EntityId value, Set<EntityId> ids) {ids.add(value);}
    };

    static <T> Value<T> nullable(Value<T> inner) {
        return new Value<>() {
            public T copy(T value) throws IOException {return value == null ? null : inner.copy(value);}
            public void write(DataOutput out, T value) throws IOException {
                BOOLEAN.write(out, value != null);if (value != null) inner.write(out, value);
            }
            public T read(DataInput in) throws IOException {return BOOLEAN.read(in) ? inner.read(in) : null;}
            public void references(T value, Set<EntityId> ids) {if (value != null) inner.references(value, ids);}
        };
    }

    static <E extends Enum<E>> Value<E> enumeration(Class<E> type) {
        return scalar((out, v) -> TEXT.write(out, v.name()), in -> {
            String name = TEXT.read(in);
            try {return Enum.valueOf(type, name);}
            catch (IllegalArgumentException e) {throw new IOException("Unknown " + type.getSimpleName() + " value: " + name, e);}
        }, v -> v);
    }

    static <T> Value<List<T>> list(Value<T> item) {
        return new Value<>() {
            public List<T> copy(List<T> value) throws IOException {
                if (value == null) throw new IOException("Missing list in save");
                count(value.size(), MAX_LIST_ITEMS);
                List<T> result = new ArrayList<>(value.size());
                for (T v : value) result.add(item.copy(v));
                return result;
            }
            public void write(DataOutput out, List<T> value) throws IOException {
                out.writeInt(count(value.size(), MAX_LIST_ITEMS));
                for (T v : value) item.write(out, v);
            }
            public List<T> read(DataInput in) throws IOException {
                int size = count(in.readInt(), MAX_LIST_ITEMS);
                List<T> result = new ArrayList<>(size);
                for (int i = 0; i < size; i++) result.add(item.read(in));
                return result;
            }
            public void references(List<T> value, Set<EntityId> ids) {for (T v : value) item.references(v, ids);}
        };
    }

    static final Value<Map<Short, Boolean>> PLAYER_FLAGS = new Value<>() {
        public Map<Short, Boolean> copy(Map<Short, Boolean> value) throws IOException {
            if (value == null) throw new IOException("Missing player flags in save");
            count(value.size(), MAX_MAP_ITEMS);
            Map<Short, Boolean> result = new TreeMap<>();
            for (var entry : value.entrySet()) result.put(SHORT.copy(entry.getKey()), BOOLEAN.copy(entry.getValue()));
            return result;
        }
        public void write(DataOutput out, Map<Short, Boolean> value) throws IOException {
            var sorted = copy(value);out.writeInt(sorted.size());
            for (var entry : sorted.entrySet()) {SHORT.write(out, entry.getKey());BOOLEAN.write(out, entry.getValue());}
        }
        public Map<Short, Boolean> read(DataInput in) throws IOException {
            int size = count(in.readInt(), MAX_MAP_ITEMS);Map<Short, Boolean> result = new TreeMap<>();
            for (int i = 0; i < size; i++) {
                short key = SHORT.read(in);boolean value = BOOLEAN.read(in);
                if (result.putIfAbsent(key, value) != null) throw new IOException("Duplicate player flag in save");
            }
            return result;
        }
    };

    static int count(int count, int max) throws IOException {
        if (count < 0 || count > max) throw new IOException("Save length outside bounds: " + count);
        return count;
    }

    private SaveValues() { }
}
