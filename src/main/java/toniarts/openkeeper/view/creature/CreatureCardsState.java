/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.creature;

import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.state.session.PresentationEvent.Vec3;
import toniarts.openkeeper.gui.nifty.AbstractCreatureCardControl.CreatureUIState;
import toniarts.openkeeper.gui.nifty.WorkerAmountControl;

/** Last displayed creature observations and the order used by card clicks. */
public record CreatureCardsState(short playerId, short workerId, List<Observation> creatures,
        List<Group> groups, List<Short> cards, Map<Short,Integer> nextIndices,
        WorkerAmountControl.State mode, int firstVisible) {
    public static final int MAX_CREATURES = 65_536;
    public CreatureCardsState {
        type(playerId);type(workerId);Objects.requireNonNull(mode);
        creatures = List.copyOf(creatures);groups = List.copyOf(groups);cards = List.copyOf(cards);nextIndices = Map.copyOf(nextIndices);
        if (creatures.size() > MAX_CREATURES || groups.size() > 256 || cards.size() > 255 || nextIndices.size() > 256
                || firstVisible < 0 || firstVisible > Math.max(0, cards.size()-1))
            throw new IllegalArgumentException("Invalid creature card size/scroll");
        var observations = new HashMap<EntityId,Observation>();
        for (var value : creatures) if (observations.put(value.id(),value) != null)
            throw new IllegalArgumentException("Duplicate card creature");
        var grouped = new HashSet<EntityId>();var types = new HashSet<Short>();var visible = new HashSet<Short>();
        for (var group : groups) {
            if (!types.add(group.type())) throw new IllegalArgumentException("Duplicate card species");
            if (group.type() != workerId && !group.ids().isEmpty()) visible.add(group.type());
            for (var id : group.ids()) {
                var value = observations.get(id);
                if (value == null || !Objects.equals(value.type(),group.type()) || !grouped.add(id))
                    throw new IllegalArgumentException("Inconsistent card membership");
            }
        }
        if (creatures.stream().anyMatch(c -> (c.type()!=null) != grouped.contains(c.id()))
                || cards.stream().distinct().count() != cards.size() || !visible.equals(new HashSet<>(cards)))
            throw new IllegalArgumentException("Incomplete card membership/order");
        for (var entry : nextIndices.entrySet()) {
            type(entry.getKey());
            if (!types.contains(entry.getKey()) || entry.getValue()<0 || entry.getValue()>MAX_CREATURES)
                throw new IllegalArgumentException("Invalid creature cycling position");
        }
    }
    /** A null type is a query member awaiting its first HUD observation. */
    public record Observation(EntityId id, Short type, Vec3 position, CreatureUIState activity, boolean dead) {
        public Observation {
            if (id == null || id.getId()<0) throw new IllegalArgumentException("Invalid card entity");
            if (type == null) {
                if (position != null || activity != null || dead) throw new IllegalArgumentException("Invalid pending card observation");
            } else {
                CreatureCardsState.type(type);
                if (activity != CreatureUIState.IDLE && activity != CreatureUIState.BUSY && activity != CreatureUIState.FIGHT)
                    throw new IllegalArgumentException("Invalid observed activity");
            }
        }
        public boolean selectable() {return type != null && position != null && !dead;}
    }
    public record Group(short type, List<EntityId> ids) {
        public Group {
            CreatureCardsState.type(type);ids = List.copyOf(ids);
            if (ids.size()>MAX_CREATURES) throw new IllegalArgumentException("Too many species members");
        }
    }
    private static void type(short value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Invalid card identity");
    }
}
