/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller;

import com.simsilica.es.EntityData;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;

/** Authoritative owned defense counts used by campaign conditions, not workshop stock. */
public final class DefenseCounts {
    public static final int BLUEPRINTS = 8;
    public static final int BUILT = 16;
    private static final int BOULDER_SHOT = 26;
    private static final int BOULDER_OBJECT = 74;
    private DefenseCounts() {}

    public static int doors(EntityData data, short owner, short type, int flags) {
        int count = 0;
        for (var id : data.findEntities(null, DoorComponent.class, Owner.class)) {
            if (data.getComponent(id, Owner.class).ownerId != owner) continue;
            var door = data.getComponent(id, DoorComponent.class);
            if (matches(door.doorId, door.blueprint, type, flags)) count++;
        }
        return count;
    }

    public static int traps(EntityData data, IKwdFile definition, short owner, short type, int flags) {
        int count = 0;
        for (var id : data.findEntities(null, TrapComponent.class, Owner.class)) {
            if (data.getComponent(id, Owner.class).ownerId != owner
                    || data.getComponent(id, DoorComponent.class) != null) continue; // Embedded door weapons are not traps.
            var trap = data.getComponent(id, TrapComponent.class);
            // This is the existing persisted blueprint value written by the trap factory.
            var view = data.getComponent(id, TrapViewState.class);
            if (matches(trap.trapId, view != null && view.blueprint, type, flags)) count++;
        }
        // Retail includes rolling boulder objects only for an unqualified, type-specific count.
        var trap = type == 0 || (flags & (BLUEPRINTS | BUILT)) != 0 ? null : definition.getTrapById(type);
        if (trap != null && trap.getShotTypeId() == BOULDER_SHOT) {
            for (var id : data.findEntities(null, ObjectComponent.class, Owner.class))
                if (data.getComponent(id, Owner.class).ownerId == owner
                        && data.getComponent(id, ObjectComponent.class).objectId == BOULDER_OBJECT) count++;
        }
        return count;
    }

    private static boolean matches(short actualType, boolean blueprint, short requestedType, int flags) {
        // Native flag branches take precedence over the type filter, including when both bits are set.
        if ((flags & BLUEPRINTS) != 0) return blueprint;
        if ((flags & BUILT) != 0) return !blueprint;
        return requestedType == 0 || actualType == requestedType;
    }
}
