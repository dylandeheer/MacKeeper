/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import com.simsilica.es.base.EntityIdGenerator;
import com.simsilica.es.base.MemStringIndex;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Authoritative entity store with an inspectable allocator and detached snapshots. */
public final class GameEntityData extends DefaultEntityData {
    public static final int MAX_ENTITIES = 1_000_000;
    private final Ids ids;
    private final Set<EntityId> alive = ConcurrentHashMap.newKeySet();
    private final Set<Class<? extends EntityComponent>> types = ConcurrentHashMap.newKeySet();

    public record EntityState(long id, List<ComponentSnapshots.Snapshot> components) {
        public EntityState {
            if (id < 0) throw new IllegalArgumentException("Negative entity ID");
            components = List.copyOf(components);
            if (components.size() > ComponentSnapshots.typeCount()) throw new IllegalArgumentException("Too many components");
            Set<String> names = new HashSet<>();
            for (var component : components) if (!names.add(component.type())) throw new IllegalArgumentException("Duplicate component on entity " + id);
        }
    }

    public record Snapshot(long nextEntityId, List<EntityState> entities) {
        public Snapshot {
            if (nextEntityId < 0) throw new IllegalArgumentException("Negative allocator state");
            entities = List.copyOf(entities);
            if (entities.size() > MAX_ENTITIES) throw new IllegalArgumentException("Too many entities");
            Set<Long> seen = new HashSet<>();
            for (var entity : entities) {
                if (entity.id() >= nextEntityId || !seen.add(entity.id())) throw new IllegalArgumentException("Invalid or duplicate entity ID " + entity.id());
                for (var component : entity.components()) for (var reference : component.referencedEntityIds()) {
                    // A removed target may remain referenced until the next
                    // system update. Preserve these allocated tombstone IDs;
                    // reject IDs that were never allocated in this world.
                    if (reference.getId() >= nextEntityId) throw new IllegalArgumentException("Reference exceeds allocator state: " + reference);
                }
            }
        }
    }

    private static final class Ids implements EntityIdGenerator {
        private final AtomicLong next;
        Ids(long next) {this.next = new AtomicLong(next);}
        public long nextEntityId() {
            for (;;) {
                long value = next.get();
                if (value == Long.MAX_VALUE) throw new IllegalStateException("Entity ID space exhausted");
                if (next.compareAndSet(value, value + 1)) return value;
            }
        }
        void observe(EntityId id) {
            if (id.getId() < 0 || id.getId() == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid entity ID: " + id);
            next.accumulateAndGet(id.getId() + 1, Math::max);
        }
    }

    public GameEntityData() {this(new Ids(0));}
    private GameEntityData(Ids ids) {
        super(ids);this.ids = ids;
        // DefaultEntityData initializes its string index only for its exact
        // class, so subclasses must provide it explicitly.
        setStringIndex(new MemStringIndex());
    }

    @Override public EntityId createEntity() {
        EntityId id = super.createEntity();alive.add(id);return id;
    }

    @Override public <T extends EntityComponent> void setComponent(EntityId id, T component) {
        Objects.requireNonNull(id);Objects.requireNonNull(component);
        ids.observe(id);types.add(component.getClass());alive.add(id);
        super.setComponent(id, component);
    }

    @Override public void removeEntity(EntityId id) {
        super.removeEntity(id);alive.remove(id);
    }

    /**
     * Call only while all writers are excluded by the simulation boundary.
     * Includes empty entities; an unsupported live component fails capture.
     */
    public Snapshot capture() throws IOException {
        if (alive.size() > MAX_ENTITIES) throw new IOException("Too many entities to save");
        Map<Long, List<ComponentSnapshots.Snapshot>> states = new TreeMap<>();
        for (EntityId id : alive) states.put(id.getId(), new ArrayList<>());
        for (var type : types) for (EntityId id : getEntityIds(type)) {
            var component = getComponent(id, type);
            if (component == null) throw new IOException("Entity changed during snapshot: " + id);
            // Component presence remains authoritative if a concurrent update
            // resurrected a component while removeEntity was finishing. This
            // matches DefaultEntityData's existing per-component semantics.
            var components = states.computeIfAbsent(id.getId(), ignored -> new ArrayList<>());
            try {components.add(ComponentSnapshots.capture(component));}
            catch (IOException e) {throw new IOException("Cannot capture entity " + id + " component " + type.getSimpleName(), e);}
        }
        List<EntityState> entities = new ArrayList<>(states.size());
        for (var entry : states.entrySet()) {
            entry.getValue().sort(Comparator.comparing(ComponentSnapshots.Snapshot::type));
            entities.add(new EntityState(entry.getKey(), entry.getValue()));
        }
        try {return new Snapshot(ids.next.get(), entities);}
        catch (IllegalArgumentException e) {throw new IOException("Invalid entity snapshot", e);}
    }

    /** Build a separate store. No current session is replaced or modified. */
    public static GameEntityData restore(Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot);
        var restored = new GameEntityData(new Ids(snapshot.nextEntityId()));
        try {
            for (var entity : snapshot.entities()) {
                EntityId id = new EntityId(entity.id());restored.alive.add(id);
                for (var component : entity.components()) restored.setComponent(id, ComponentSnapshots.restore(component));
            }
            return restored;
        } catch (IOException | RuntimeException | Error failure) {
            restored.close();throw failure;
        }
    }

    @Override public void close() {
        super.close();alive.clear();types.clear();
    }
}
