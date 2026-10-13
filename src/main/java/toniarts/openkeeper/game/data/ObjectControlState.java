/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import com.simsilica.es.EntityId;

/** Cached object wrapper identity/definition. Live object type remains an ECS component. */
public record ObjectControlState(EntityId entityId, short definition) {
    public ObjectControlState {
        if (entityId == null || entityId.getId() < 0 || definition < 0 || definition > 255)
            throw new IllegalArgumentException("Invalid object controller binding");
    }
}
