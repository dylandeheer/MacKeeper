/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import java.util.Objects;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState.Type;

/** Persistent tool choice; pointer, hover, pressed keys and unfinished drags are transient. */
public record InteractionSelection(short playerId, boolean enabled, Type type, int itemId) {
    public InteractionSelection {
        Objects.requireNonNull(type);
        if (playerId < 0 || playerId > 255 || itemId < 0 || itemId > 32767)
            throw new IllegalArgumentException("Invalid interaction identity");
        if (switch (type) {case NONE, SELL, STUFF_IN_HAND -> itemId != 0;default -> itemId == 0;})
            throw new IllegalArgumentException("Invalid tool item");
    }
    public void validateDefinitions(IKwdFile map) {
        Objects.requireNonNull(map);
        boolean installed = switch (type) {
            case ROOM -> map.getRoomById(itemId) != null;
            case SPELL -> map.getKeeperSpellById(itemId) != null;
            case TRAP -> map.getTrapById(itemId) != null;
            case DOOR -> map.getDoorById(itemId) != null;
            default -> true;
        };
        if (!installed) throw new IllegalArgumentException("Selected tool definition is not installed");
    }
}
