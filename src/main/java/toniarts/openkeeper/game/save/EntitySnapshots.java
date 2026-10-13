/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.ArrayList;

/** Versioned ECS section codec. This is not a complete game-save container. */
public final class EntitySnapshots {
    static final int MAGIC = 0x4d4b4553; // MKES
    static final int VERSION = 1;
    static final int MAX_BYTES = 64 * 1024 * 1024;

    public static byte[] encode(GameEntityData.Snapshot snapshot) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new OutputStream() {
            private int size;
            public void write(int value) throws IOException {reserve(1);bytes.write(value);}
            public void write(byte[] b, int offset, int length) throws IOException {reserve(length);bytes.write(b, offset, length);}
            private void reserve(int length) throws IOException {
                if (length > MAX_BYTES - size) throw new IOException("Entity snapshot exceeds size limit");
                size += length;
            }
        })) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(snapshot.nextEntityId());out.writeInt(snapshot.entities().size());
            for (var entity : snapshot.entities()) {
                out.writeLong(entity.id());out.writeShort(entity.components().size());
                for (var component : entity.components()) {
                    byte[] encoded = ComponentSnapshots.encode(component);out.writeInt(encoded.length);out.write(encoded);
                }
            }
        }
        return bytes.toByteArray();
    }

    public static GameEntityData.Snapshot decode(byte[] encoded) throws IOException {
        SaveValues.count(encoded.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a MacKeeper entity snapshot");
            if (in.readInt() != VERSION) throw new IOException("Unsupported entity snapshot version");
            long next = in.readLong();if (next < 0) throw new IOException("Negative entity allocator state");
            int count = SaveValues.count(in.readInt(), GameEntityData.MAX_ENTITIES);
            // Even an empty entity needs ten bytes; reject impossible lengths
            // before reserving a large array for truncated input.
            if (count > in.available() / 10) throw new IOException("Truncated entity table");
            var entities = new ArrayList<GameEntityData.EntityState>(count);
            for (int i = 0; i < count; i++) {
                long id = in.readLong();int size = SaveValues.count(in.readUnsignedShort(), ComponentSnapshots.typeCount());
                var components = new ArrayList<ComponentSnapshots.Snapshot>(size);
                for (int j = 0; j < size; j++) {
                    int length = SaveValues.count(in.readInt(), ComponentSnapshots.MAX_COMPONENT_BYTES);
                    if (length > in.available()) throw new IOException("Truncated component record");
                    byte[] record = new byte[length];in.readFully(record);components.add(ComponentSnapshots.decode(record));
                }
                entities.add(new GameEntityData.EntityState(id, components));
            }
            if (in.read() != -1) throw new IOException("Trailing entity snapshot data");
            return new GameEntityData.Snapshot(next, entities);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid entity snapshot", e);
        }
    }

    private EntitySnapshots() { }
}
