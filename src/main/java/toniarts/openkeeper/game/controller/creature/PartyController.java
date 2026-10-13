/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.controller.creature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Function;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.data.PartyState;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.door.IDoorController;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Terrain;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.utils.Utils;

/**
 * Represents a party, a group of creatures. When the Leader is incapacitated, a
 * new Leader is chosen at random. Any triggers and actions which hung off the
 * old Leader are transferred to the new.
 *
 * @author ArchDemon
 */
public final class PartyController implements IPartyController {

    private final LongSupplier idAllocator;
    private final java.util.random.RandomGenerator random;
    private final List<Thing.GoodCreature> authoredMembers;

    private long id;
    private final short partyId;
    private final int triggerId;
    private final String name;
    private final SequencedMap<Thing.GoodCreature, ICreatureController> members;
    private PartyType type;
    private ICreatureController leader;
    private boolean created = false;

    public PartyController(Thing.HeroParty heroParty, LongSupplier idAllocator) {
        this(heroParty, idAllocator, new toniarts.openkeeper.game.random.SimulationRandom());
    }

    public PartyController(Thing.HeroParty heroParty, LongSupplier idAllocator, java.util.random.RandomGenerator random) {
        this.random = java.util.Objects.requireNonNull(random);
        this.idAllocator = java.util.Objects.requireNonNull(idAllocator);
        authoredMembers = List.copyOf(heroParty.getHeroPartyMembers());
        partyId = heroParty.getId();
        name = heroParty.getName();
        triggerId = heroParty.getTriggerId();
        members = LinkedHashMap.newLinkedHashMap(heroParty.getHeroPartyMembers().size());
        for (Thing.GoodCreature creature : heroParty.getHeroPartyMembers()) {
            members.put(creature, null);
        }
    }

    public PartyState captureState() {
        var values = new ArrayList<PartyState.Member>();
        for (var entry : members.entrySet()) {
            int index = -1;
            for (int i = 0; i < authoredMembers.size(); i++) if (authoredMembers.get(i) == entry.getKey()) {index = i;break;}
            if (index < 0) throw new IllegalStateException("Party member has no authored identity");
            values.add(new PartyState.Member(index, entry.getValue() == null ? null : entry.getValue().getEntityId()));
        }
        return new PartyState(id, partyId, created, type, leader == null ? null : leader.getEntityId(), values);
    }


    public static PartyController restoreState(PartyState saved, Thing.HeroParty definition,
            LongSupplier allocator, Function<EntityId, ICreatureController> creatures, java.util.random.RandomGenerator random) {
        if (definition == null || definition.getId() != saved.definitionId())
            throw new IllegalArgumentException("Missing party definition");
        var result = new PartyController(definition, allocator, random);
        result.members.clear();
        for (var member : saved.members()) {
            if (member.definitionIndex() >= result.authoredMembers.size()) throw new IllegalArgumentException("Missing authored party member");
            var key = result.authoredMembers.get(member.definitionIndex());
            if (result.members.containsKey(key)) throw new IllegalArgumentException("Aliased authored party member");
            result.members.put(key, member.creature() == null ? null : resolve(creatures, member.creature()));
        }
        result.id = saved.id();result.created = saved.created();result.type = saved.type();
        result.leader = saved.leader() == null ? null : resolve(creatures, saved.leader());
        return result;
    }

    private static ICreatureController resolve(Function<EntityId, ICreatureController> creatures, EntityId id) {
        var creature = creatures.apply(id);
        if (creature == null || !id.equals(creature.getEntityId())) throw new IllegalArgumentException("Missing/mismatched party creature");
        return creature;
    }

    @Override
    public void create() {
        if (created) throw new IllegalStateException("Party already created");
        long allocated = idAllocator.getAsLong();
        if (allocated < 1) throw new IllegalStateException("Invalid allocated party identity");
        id = allocated;
        created = true;
    }

    @Override
    public boolean isCreated() {
        return created;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public short getPartyId() {
        return partyId;
    }

    @Override
    public int getTriggerId() {
        return triggerId;
    }

    @Override
    public PartyType getType() {
        return type;
    }

    @Override
    public void setType(PartyType type) {
        this.type = type;
    }

    /**
     * Get members
     *
     * @see #getActualMembers()
     * @return the members
     */
    @Override
    public Set<Thing.GoodCreature> getMembers() {
        return members.keySet();
    }

    @Override
    public void addMemberInstance(Thing.GoodCreature creature, ICreatureController entity) {
        members.put(creature, entity);

        if (leader == null && creature.getFlags().contains(Thing.Creature.CreatureFlag.LEADER)) {
            leader = entity;
        }
    }

    @Override
    public Collection<ICreatureController> getActualMembers() {
        return members.values();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ICreatureController getPartyLeader() {
        checkPartyLeader();
        return leader;
    }

    /**
     * Test if the given creature is the party leader
     *
     * @param creature the creature to test
     * @return true if the creature is the party leader
     */
    @Override
    public boolean isPartyLeader(ICreatureController creature) {
        return creature.equals(getPartyLeader());
    }

    /**
     * A party member is incapacitated, if it was the leader. Swap the duties
     * and objectives
     */
    private void checkPartyLeader() {
        if (leader != null && leader.isIncapacitated()) {
            List<ICreatureController> leaderCandidates = new ArrayList<>(getActualMembers());
            Iterator<ICreatureController> iter = leaderCandidates.iterator();
            while (iter.hasNext()) {
                ICreatureController c = iter.next();
                if (c.isIncapacitated()) {
                    iter.remove();
                }
            }

            // See if any left
            if (!leaderCandidates.isEmpty()) {
                ICreatureController oldLeader = leader;
                leader = Utils.getRandomItem(leaderCandidates, random);

                // Swap duties
                // TODO: this works only when the old leader hasn't already died, so need to signal this somehow (PartySystem?)
                leader.setObjectiveTargetActionPointId(oldLeader.getObjectiveTargetActionPointId());
                leader.setObjective(oldLeader.getObjective());
            }
        }
    }

    /**
     * Whether this party has access to workers. Defines the tactic we can use
     * to go kill the enemy.
     *
     * @return does the party contain workers
     */
    @Override
    public boolean isWorkersAvailable() {
        for (ICreatureController creature : getActualMembers()) {
            if (!creature.isIncapacitated() && creature.isWorker()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public short getOwnerId() {
        return Player.GOOD_PLAYER_ID; // Always good player
    }

    @Override
    public boolean canFly() {
        for (ICreatureController creature : getActualMembers()) {
            if (!creature.isIncapacitated() && !creature.canFly()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canWalkOnWater() {
        for (ICreatureController creature : getActualMembers()) {
            if (!creature.isIncapacitated() && !creature.canWalkOnWater()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canWalkOnLava() {
        for (ICreatureController creature : getActualMembers()) {
            if (!creature.isIncapacitated() && !creature.canWalkOnLava()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Float getCost(IMapTileInformation from, IMapTileInformation to, IMapController mapController, IEntityPositionLookup entityPositionLookup) {
        Float cost = IPartyController.super.getCost(from, to, mapController, entityPositionLookup);
        if (cost == null) {

            // No path by ordinary means, but we might want to tunnel or smash our way through obstacles
            Terrain terrain = mapController.getTerrain(to);
            if (terrain.getFlags().contains(Terrain.TerrainFlag.SOLID) && isWorkersAvailable()) {
                if (terrain.getFlags().contains(Terrain.TerrainFlag.DWARF_CAN_DIG_THROUGH)) {
                    return 5f; // Dig our selves in
                }
                if (terrain.getFlags().contains(Terrain.TerrainFlag.ATTACKABLE)) {
                    return 6f; // It seems that everybody can attack reinforced walls i.e. ?
                }
            }

            // Check if any obstacles lies in our path, we can smash through enemy doors but not our own locked doors
            // FIXME: now just doors
            for (IDoorController doorController : entityPositionLookup.getEntityTypesInLocation(to, IDoorController.class)) {
                if (doorController.getOwnerId() != getOwnerId()) {
                    return 3f;
                }
            }
        }
        return cost;
    }

    @Override
    public boolean canMoveDiagonally() {

        // Maybe not a perfect solution, but if workers don't allow diagonal paths so that we don't dig diagonally
        return !isWorkersAvailable();
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 79 * hash + (int) (this.id ^ (this.id >>> 32));
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final PartyController other = (PartyController) obj;
        if (this.id != other.id) {
            return false;
        }
        return true;
    }

}
