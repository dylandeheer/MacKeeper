/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Authoritative falling and dungeon-heart construction progress, without rerunning initialization. */
public final class AnimationSnapshots {
    public record Heart(double elapsed, float delay, boolean done, List<EntityId> plugs,
            Map<EntityId,List<EntityId>> stairs, Map<EntityId,List<EntityId>> arches) {
        public Heart {
            if (!Double.isFinite(elapsed) || elapsed < 0 || !Float.isFinite(delay)) throw new IllegalArgumentException("Invalid construction time");
            plugs = List.copyOf(plugs);ordered(plugs);stairs = groups(stairs);arches = groups(arches);
            var keys = new HashSet<>(plugs);
            if (!keys.equals(stairs.keySet()) || !keys.equals(arches.keySet()) || (done && !plugs.isEmpty()))
                throw new IllegalArgumentException("Invalid construction groups");
            var stepIds = new HashSet<EntityId>();stairs.values().forEach(stepIds::addAll);
            var archIds = new HashSet<EntityId>();arches.values().forEach(archIds::addAll);
            if (!Collections.disjoint(keys,stepIds) || !Collections.disjoint(keys,archIds) || !Collections.disjoint(stepIds,archIds))
                throw new IllegalArgumentException("Conflicting construction roles");
        }
    }
    public record State(LifetimeSnapshots.Clock fall, Heart heart) {
        public State {Objects.requireNonNull(fall);Objects.requireNonNull(heart);}
    }
    private static final int MAGIC = 0x4d4b414e, VERSION = 1, MAX_BYTES = 4*1024*1024, MAX_ENTRIES = 65536;
    private static void ordered(List<EntityId> ids) {
        if (ids.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many animation references");
        long previous = -1;
        for (var id : ids) {
            if (id.getId() <= previous) throw new IllegalArgumentException("Invalid animation reference order");
            previous = id.getId();
        }
    }
    private static Map<EntityId,List<EntityId>> groups(Map<EntityId,List<EntityId>> values) {
        if (values.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many animation groups");
        var copy = new TreeMap<EntityId,List<EntityId>>();int total = 0;
        for (var entry : values.entrySet()) {
            if (entry.getKey().getId() < 0) throw new IllegalArgumentException("Invalid animation group");
            var ids = List.copyOf(entry.getValue());ordered(ids);total += ids.size();
            if (total > MAX_ENTRIES) throw new IllegalArgumentException("Too many animation group references");
            copy.put(entry.getKey(),ids);
        }
        return Collections.unmodifiableMap(copy);
    }
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);DOUBLE.write(out,state.fall().elapsed());
            var heart = state.heart();DOUBLE.write(out,heart.elapsed());FLOAT.write(out,heart.delay());BOOLEAN.write(out,heart.done());
            list(ENTITY_ID).write(out,heart.plugs());groups(out,heart.stairs());groups(out,heart.arches());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported animation section");
            var fall = new LifetimeSnapshots.Clock(DOUBLE.read(in));
            var state = new State(fall,new Heart(DOUBLE.read(in),FLOAT.read(in),BOOLEAN.read(in),list(ENTITY_ID).read(in),groups(in),groups(in)));
            if (in.read() != -1) throw new IOException("Trailing animation data");return state;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid animation section",e);}
    }
    private static void groups(DataOutput out,Map<EntityId,List<EntityId>> values) throws IOException {
        out.writeInt(values.size());for (var entry : values.entrySet()) {ENTITY_ID.write(out,entry.getKey());list(ENTITY_ID).write(out,entry.getValue());}
    }
    private static Map<EntityId,List<EntityId>> groups(DataInput in) throws IOException {
        int n = count(in.readInt(),MAX_ENTRIES), total = 0;var result = new TreeMap<EntityId,List<EntityId>>();
        for (int i=0;i<n;i++) {
            var id = ENTITY_ID.read(in);var entries = list(ENTITY_ID).read(in);total += entries.size();
            count(total,MAX_ENTRIES);if (result.put(id,entries) != null) throw new IOException("Duplicate animation group");
        }
        return result;
    }
    private AnimationSnapshots() { }
}
