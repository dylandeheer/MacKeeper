/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import com.simsilica.es.EntityId;
import java.util.List;
import toniarts.openkeeper.game.controller.creature.PartyType;

/** Party values without leader selection or objective transfer. */
public record PartyState(long id, short definitionId, boolean created, PartyType type,
        EntityId leader, List<Member> members) {
    public record Member(int definitionIndex, EntityId creature) {
        public Member {
            if (definitionIndex < 0 || definitionIndex >= 65_536 || creature != null && creature.getId() < 0)
                throw new IllegalArgumentException("Invalid party member");
        }
    }
    public PartyState {
        if (definitionId < 0 || definitionId > 255 || (created ? id < 1 : id != 0)
                || leader != null && leader.getId() < 0) throw new IllegalArgumentException("Invalid party identity");
        members = List.copyOf(members);
        if (members.size() > 65_536 || members.stream().map(Member::definitionIndex).distinct().count() != members.size())
            throw new IllegalArgumentException("Invalid party member order");
    }
}
