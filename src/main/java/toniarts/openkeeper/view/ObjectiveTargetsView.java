/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import com.jme3.math.Vector3f;
import com.simsilica.es.*;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.LevelObjectives.Target;

/** Render-thread subscriptions. Reading the displayed targets never queries the server. */
public final class ObjectiveTargetsView implements AutoCloseable {
    public record Observation(Target target, Short creatureType, Vector3f position) {}
    private final EntitySet objectives, creatures, positions, held;
    private List<Observation> observations = List.of();
    private boolean closed;

    public ObjectiveTargetsView(EntityData data) {
        var opened = new ArrayList<EntitySet>();
        try {
            opened.add(objectives = data.getEntities(LevelObjective.class));
            opened.add(creatures = data.getEntities(PlayerObjective.class, CreatureComponent.class));
            opened.add(positions = data.getEntities(PlayerObjective.class, Position.class));
            opened.add(held = data.getEntities(PlayerObjective.class, InHand.class));
        } catch (RuntimeException | Error failure) {
            opened.forEach(EntitySet::release);
            throw failure;
        }
    }

    public void update() {
        if (closed) throw new IllegalStateException("Objective view is closed");
        objectives.applyChanges();
        creatures.applyChanges();
        positions.applyChanges();
        held.applyChanges();
        var targets = new ArrayList<Target>();
        for (var entity : objectives) {
            var value = entity.get(LevelObjective.class);
            targets.add(new Target(entity.getId(), value.slot, value.playerId, value.creatureId, value.objective));
        }
        targets.sort(Comparator.comparingInt(Target::slot));
        var next = new ArrayList<Observation>();
        for (var target : targets) {
            var creature = target.creatureId() == null ? null : creatures.getEntity(target.creatureId());
            var definition = creature == null ? null : creature.get(CreatureComponent.class);
            var located = target.creatureId() == null ? null : positions.getEntity(target.creatureId());
            var position = located == null ? null : located.get(Position.class);
            next.add(new Observation(target, definition == null ? null : definition.creatureId,
                    definition == null || position == null || position.position == null || held.containsId(target.creatureId())
                            ? null : position.position.clone()));
        }
        observations = List.copyOf(next);
    }

    public List<Observation> observations() { return observations; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        objectives.release();
        creatures.release();
        positions.release();
        held.release();
        observations = List.of();
    }
}
