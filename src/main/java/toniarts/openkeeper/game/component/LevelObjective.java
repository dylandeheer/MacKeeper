/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.component;

import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.data.ObjectiveType;

/** One occupied slot in the shared six-target campaign objective list. */
public final class LevelObjective implements EntityComponent {
    public int slot;
    public short playerId;
    public EntityId creatureId;
    public ObjectiveType objective;

    public LevelObjective() {} // Network and archive construction.
    public LevelObjective(int slot, short playerId, EntityId creatureId, ObjectiveType objective) {
        this.slot=slot;this.playerId=playerId;this.creatureId=creatureId;this.objective=objective;
    }
}
