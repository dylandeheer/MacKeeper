/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.utils.Point;

/** Presentation clocks not represented by authoritative ECS components. */
public record WorldViewState(short playerId, int width, int height, Flash flash, Map<EntityId, Flower> flowers) {
    public static final int MAX_TILES = 1_048_576, MAX_FLOWERS = 65_536;
    public record Flash(boolean enabled, float tick, boolean phase, Set<Point> points, Set<Point> painted) {
        public Flash {
            finiteTime(tick);points = Set.copyOf(points);painted = Set.copyOf(painted);
            if (points.size() > MAX_TILES || !points.containsAll(painted) || !phase && !painted.isEmpty())
                throw new IllegalArgumentException("Invalid tile flash membership");
        }
    }
    public enum Kind {CREATURE, DOOR, TRAP}
    public enum Status {LEVEL, ACTIVITY, MOOD, REASON}
    public record Cycle(Status status, float statusTime, float redrawTime) {
        public Cycle {Objects.requireNonNull(status);finiteTime(statusTime);finiteTime(redrawTime);}
    }
    public record Flower(Kind kind, short definitionId, boolean visible, float elapsed, float deadline, Cycle cycle) {
        public Flower {
            Objects.requireNonNull(kind);finiteTime(elapsed);finiteTime(deadline);
            if (definitionId <= 0 || (kind == Kind.CREATURE) != (cycle != null))
                throw new IllegalArgumentException("Invalid unit flower definition/cycle");
        }
        public void validateDefinition(IKwdFile definition) {
            Object value = switch(kind) {
                case CREATURE -> definition.getCreature(definitionId);
                case DOOR -> definition.getDoorById(definitionId);
                case TRAP -> definition.getTrapById(definitionId);
            };
            if (value == null) throw new IllegalArgumentException("Missing unit flower definition");
        }
    }
    public WorldViewState {
        Objects.requireNonNull(flash);flowers = Map.copyOf(flowers);
        if (playerId < 0 || playerId > 255 || width <= 0 || height <= 0 || (long) width * height > MAX_TILES
                || flowers.size() > MAX_FLOWERS) throw new IllegalArgumentException("Invalid world view owner/size");
        for (Point p : flash.points()) if (p.x < 0 || p.y < 0 || p.x >= width || p.y >= height)
            throw new IllegalArgumentException("Flashing tile outside map");
        for (EntityId id : flowers.keySet()) if (id.getId() < 0) throw new IllegalArgumentException("Invalid unit flower entity");
    }
    private static void finiteTime(float time) {
        if (!Float.isFinite(time) || time < 0) throw new IllegalArgumentException("Invalid presentation time");
    }
}
