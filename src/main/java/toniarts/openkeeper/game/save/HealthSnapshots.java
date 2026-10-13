/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Health clocks and observations whose changes reset regeneration or wake recovering creatures. */
public final class HealthSnapshots {
    public record Settings(int timeToDeath, int regeneration, int imprisonedRegeneration) { }
    public record State(double elapsed, Settings settings, List<Short> players, List<EntityId> observedHealth,
            List<EntityId> dirtyHealth, List<EntityId> observedRecuperating) {
        public State {
            if (!Double.isFinite(elapsed) || elapsed < 0) throw new IllegalArgumentException("Invalid health clock");
            Objects.requireNonNull(settings);players = List.copyOf(players);
            observedHealth = List.copyOf(observedHealth);dirtyHealth = List.copyOf(dirtyHealth);
            observedRecuperating = List.copyOf(observedRecuperating);
            if (players.size() > 256) throw new IllegalArgumentException("Too many health players");
            int previous = -1;
            for (short id : players) {
                if (id <= previous || id > 255) throw new IllegalArgumentException("Invalid health player order");
                previous = id;
            }
            ordered(observedHealth);ordered(dirtyHealth);ordered(observedRecuperating);
            if (!new HashSet<>(observedHealth).containsAll(dirtyHealth)) throw new IllegalArgumentException("Unknown dirty health observation");
        }
    }
    private static final int MAGIC = 0x4d4b4854, VERSION = 1, MAX_BYTES = 25*1024*1024;
    private static void ordered(List<EntityId> ids) {
        if (ids.size() > GameEntityData.MAX_ENTITIES) throw new IllegalArgumentException("Too many health observations");
        long previous = -1;
        for (var id : ids) {
            if (id.getId() <= previous) throw new IllegalArgumentException("Invalid health observation order");
            previous = id.getId();
        }
    }
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);DOUBLE.write(out,state.elapsed());
            out.writeInt(state.settings().timeToDeath());out.writeInt(state.settings().regeneration());out.writeInt(state.settings().imprisonedRegeneration());
            list(SHORT).write(out,state.players());ids(out,state.observedHealth());ids(out,state.dirtyHealth());ids(out,state.observedRecuperating());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported health section");
            double elapsed = DOUBLE.read(in);var settings = new Settings(in.readInt(),in.readInt(),in.readInt());
            int n = count(in.readInt(),256);var players = new ArrayList<Short>();
            for (int i=0;i<n;i++) players.add(in.readShort());
            var state = new State(elapsed,settings,players,ids(in),ids(in),ids(in));
            if (in.read() != -1) throw new IOException("Trailing health data");return state;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid health section",e);}
    }
    private static void ids(DataOutput out,List<EntityId> ids) throws IOException {
        out.writeInt(ids.size());for (var id : ids) ENTITY_ID.write(out,id);
    }
    private static List<EntityId> ids(DataInput in) throws IOException {
        int n = count(in.readInt(),GameEntityData.MAX_ENTITIES);var ids = new ArrayList<EntityId>();
        for (int i=0;i<n;i++) ids.add(ENTITY_ID.read(in));return ids;
    }
    private HealthSnapshots() { }
}
