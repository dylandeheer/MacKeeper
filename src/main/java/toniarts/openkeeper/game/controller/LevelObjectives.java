/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller;

import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.data.ObjectiveType;

/** Simulation-thread commands; all durable values live in replicated, saved ECS components. */
public final class LevelObjectives {
    public static final int CAPACITY=6;
    public record Target(EntityId entryId,int slot,short playerId,EntityId creatureId,ObjectiveType objective) {}
    private LevelObjectives() {}

    /** Detached values, in authored slot order. Empty slots are deliberately not compacted. */
    public static List<Target> targets(EntityData data) {
        var result=new ArrayList<Target>();
        for(var id:data.findEntities(null,LevelObjective.class)) {
            var value=data.getComponent(id,LevelObjective.class);
            if(value!=null)result.add(new Target(id,value.slot,value.playerId,value.creatureId,value.objective));
        }
        result.sort(Comparator.comparingInt(Target::slot));
        return List.copyOf(result);
    }

    public static void validate(EntityData data,Set<Short> players) {
        var slots=new HashSet<Integer>();
        for(var target:targets(data)) {
            if(target.slot()<0||target.slot()>=CAPACITY||!slots.add(target.slot()))
                throw new IllegalArgumentException("Invalid or duplicate campaign objective slot");
            if(target.creatureId()==null ? !players.contains(target.playerId())||target.objective()!=null
                    : target.playerId()!=0||target.objective()==null)
                throw new IllegalArgumentException("Invalid campaign objective target");
        }
    }

    public static boolean addPlayer(EntityData data,short playerId) {return add(data,playerId,null,null);}
    public static boolean addCreature(EntityData data,EntityId creature,ObjectiveType kind) {
        Objects.requireNonNull(creature);Objects.requireNonNull(kind);
        boolean added=add(data,(short)0,creature,kind);
        if(added)refreshMarker(data,creature);
        return added;
    }
    private static boolean add(EntityData data,short player,EntityId creature,ObjectiveType kind) {
        var occupied=new BitSet(CAPACITY);for(var target:targets(data))occupied.set(target.slot());
        int slot=occupied.nextClearBit(0);if(slot>=CAPACITY)return false;
        data.setComponent(data.createEntity(),new LevelObjective(slot,player,creature,kind));return true;
    }
    /** Retail off removes the first matching creature slot, not every objective or any player slot. */
    public static void removeCreature(EntityData data,EntityId creature) {
        for(var target:targets(data))if(creature.equals(target.creatureId())){data.removeEntity(target.entryId());break;}
        refreshMarker(data,creature);
    }
    private static void refreshMarker(EntityData data,EntityId creature) {
        var first=targets(data).stream().filter(t->creature.equals(t.creatureId())).findFirst();
        if(first.isPresent())data.setComponent(creature,new PlayerObjective(first.get().objective()));
        else data.removeComponent(creature,PlayerObjective.class);
    }
    /** Remove targets whose original game objects have gone away, or whose keeper was defeated. */
    public static void reconcile(EntityData data,Map<Short,Keeper> players) {
        for(var target:targets(data)) {
            boolean gone;
            if(target.creatureId()!=null)gone=data.getComponent(target.creatureId(),CreatureComponent.class)==null;
            else {var keeper=players.get(target.playerId());gone=keeper==null||keeper.isDestroyed();}
            if(gone) {
                data.removeEntity(target.entryId());
                if(target.creatureId()!=null)data.removeComponent(target.creatureId(),PlayerObjective.class);
            }
        }
    }
}
