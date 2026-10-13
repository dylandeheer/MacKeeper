/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.fogofwar;

import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.fogofwar.FogState;
import toniarts.openkeeper.utils.Point;

/** Per-viewer discovery history. Entity references may name removed entities. */
public record FogSnapshot(FogState.Snapshot knowledge, float visionTime,
        List<HeartReveal> hearts, Set<Point> pendingTags, Set<EntityId> seenEnemies,
        Set<EntityId> observedHearts, EntityId possessedCreature) {
    public static final int MAX_ENTITIES = 65_536;
    public FogSnapshot {
        Objects.requireNonNull(knowledge);
        if (!Float.isFinite(visionTime) || visionTime < 0)
            throw new IllegalArgumentException("Invalid fog clock");
        hearts = List.copyOf(hearts);pendingTags = Set.copyOf(pendingTags);
        seenEnemies = ids(seenEnemies);observedHearts = ids(observedHearts);
        if (hearts.size() > MAX_ENTITIES || pendingTags.size() > knowledge.width() * knowledge.height())
            throw new IllegalArgumentException("Too much fog history");
        for (var heart : hearts) tile(heart.center(), knowledge);
        for (var point : pendingTags) tile(point, knowledge);
        if (possessedCreature != null) id(possessedCreature);
    }
    public record HeartReveal(Point center, int elapsedIntervals) {
        public HeartReveal {
            Objects.requireNonNull(center);
            if (elapsedIntervals < 0 || elapsedIntervals > 62)
                throw new IllegalArgumentException("Invalid heart reveal phase");
        }
    }
    private static Set<EntityId> ids(Set<EntityId> ids) {
        ids = Set.copyOf(ids);
        if (ids.size() > MAX_ENTITIES) throw new IllegalArgumentException("Too many fog entities");
        ids.forEach(FogSnapshot::id);return ids;
    }
    private static void id(EntityId id) {
        if (id.getId() < 0) throw new IllegalArgumentException("Invalid fog entity");
    }
    private static void tile(Point p, FogState.Snapshot map) {
        if (p.x < 0 || p.y < 0 || p.x >= map.width() || p.y >= map.height())
            throw new IllegalArgumentException("Fog tile outside map");
    }
}
