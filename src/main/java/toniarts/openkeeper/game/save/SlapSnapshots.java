/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Slap deadlines and query observations; restoring must not replay interaction effects. */
public final class SlapSnapshots {
    public record State(double elapsed, int duration, List<Short> players, Map<EntityId, Double> startTimes,
            List<EntityId> creatures, List<EntityId> objects, List<EntityId> dirtyCreatures, List<EntityId> dirtyObjects) {
        public State {
            if (!Double.isFinite(elapsed) || elapsed < 0) throw new IllegalArgumentException("Invalid slap clock");
            players = List.copyOf(players);startTimes = Map.copyOf(startTimes);
            creatures = List.copyOf(creatures);objects = List.copyOf(objects);
            dirtyCreatures = List.copyOf(dirtyCreatures);dirtyObjects = List.copyOf(dirtyObjects);
            if (players.size() > 256 || startTimes.size() > 131072) throw new IllegalArgumentException("Slap state too large");
            int previous = -1;
            for (short id : players) {
                if (id <= previous || id > 255) throw new IllegalArgumentException("Invalid slap player order");
                previous = id;
            }
            ordered(creatures);ordered(objects);ordered(dirtyCreatures);ordered(dirtyObjects);
            var known = new HashSet<>(creatures);known.addAll(objects);
            if (!known.containsAll(startTimes.keySet()) || !new HashSet<>(creatures).containsAll(dirtyCreatures)
                    || !new HashSet<>(objects).containsAll(dirtyObjects)) throw new IllegalArgumentException("Unknown slap observation");
            if (startTimes.values().stream().anyMatch(time -> !Double.isFinite(time))) throw new IllegalArgumentException("Invalid slap deadline");
        }
    }
    private static final int MAGIC = 0x4d4b534c, VERSION = 1, MAX_BYTES = 5*1024*1024;
    private static void ordered(List<EntityId> ids) {
        if (ids.size() > 65536) throw new IllegalArgumentException("Too many slap observations");
        long previous = -1;
        for (var id : ids) {
            if (id.getId() <= previous) throw new IllegalArgumentException("Invalid slap observation order");
            previous = id.getId();
        }
    }
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);DOUBLE.write(out,state.elapsed());out.writeInt(state.duration());
            list(SHORT).write(out,state.players());out.writeInt(state.startTimes().size());
            for (var entry : new TreeMap<>(state.startTimes()).entrySet()) {ENTITY_ID.write(out,entry.getKey());DOUBLE.write(out,entry.getValue());}
            list(ENTITY_ID).write(out,state.creatures());list(ENTITY_ID).write(out,state.objects());
            list(ENTITY_ID).write(out,state.dirtyCreatures());list(ENTITY_ID).write(out,state.dirtyObjects());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported slap section");
            double elapsed = DOUBLE.read(in);int duration = in.readInt();
            int n = count(in.readInt(),256);var players = new ArrayList<Short>();
            for (int i=0;i<n;i++) players.add(in.readShort());
            n = count(in.readInt(),131072);var starts = new TreeMap<EntityId,Double>();
            for (int i=0;i<n;i++) if (starts.put(ENTITY_ID.read(in),DOUBLE.read(in)) != null) throw new IOException("Duplicate slap deadline");
            var state = new State(elapsed,duration,players,starts,list(ENTITY_ID).read(in),list(ENTITY_ID).read(in),list(ENTITY_ID).read(in),list(ENTITY_ID).read(in));
            if (in.read() != -1) throw new IOException("Trailing slap data");return state;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid slap section",e);}
    }
    private SlapSnapshots() { }
}
