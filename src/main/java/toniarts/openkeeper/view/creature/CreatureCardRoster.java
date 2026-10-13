/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.creature;

import com.simsilica.es.*;
import com.simsilica.es.filter.FieldFilter;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.state.session.PresentationEvent.Vec3;
import toniarts.openkeeper.gui.nifty.AbstractCreatureCardControl.CreatureUIState;
import toniarts.openkeeper.gui.nifty.WorkerAmountControl;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.view.creature.CreatureCardsState.*;

/** Client HUD observations. Refresh only at HUD cadence; clicks never advance the ECS query. */
public final class CreatureCardRoster implements AutoCloseable {
    private final EntityData data;
    private final EntitySet members;
    private final short playerId, workerId;
    private final Map<EntityId,WatchedEntity> watched = new HashMap<>();
    private final LinkedHashMap<EntityId,Observation> observations = new LinkedHashMap<>();
    private final LinkedHashMap<Short,LinkedHashSet<EntityId>> groups = new LinkedHashMap<>();
    private final List<Short> cards = new ArrayList<>();
    private final Map<Short,Integer> indices = new HashMap<>();
    private boolean closed;

    public CreatureCardRoster(EntityData data, short playerId, short workerId) {
        this.data=data;this.playerId=playerId;this.workerId=workerId;
        members=data.getEntities(new FieldFilter(Owner.class,"ownerId",playerId),CreatureComponent.class,Health.class,Owner.class);
        members.stream().map(Entity::getId).sorted().forEach(id -> {
            observations.put(id,pending(id));watched.put(id,watch(id));
        });
    }
    private WatchedEntity watch(EntityId id) {
        // Keep subscriptions for hosted clients, including optional components.
        return data.watchEntity(id,CreatureComponent.class,CreatureAi.class,AttackTarget.class,Position.class,Death.class);
    }
    private static Observation pending(EntityId id) {return new Observation(id,null,null,null,false);}

    public void update() {
        requireOpen();members.applyChanges();
        var current = new HashSet<EntityId>();for (var e:members) current.add(e.getId());
        var changedTypes = new LinkedHashSet<Short>();
        // New members append; retained members keep their observed order across loads.
        for (var id:current.stream().sorted().toList()) if (!observations.containsKey(id)) {
            watched.put(id,watch(id));observations.put(id,pending(id));
        }
        for (var id:new ArrayList<>(observations.keySet())) {
            var old=observations.get(id);
            if (!current.contains(id)) {
                removeFromGroup(old,changedTypes);observations.remove(id);watched.remove(id).release();continue;
            }
            var entity=watched.get(id);entity.applyChanges();
            var component=entity.get(CreatureComponent.class);
            Observation next;
            if (component == null) next=pending(id);
            else {
                var ai=entity.get(CreatureAi.class);var position=entity.get(Position.class);
                var activity=ai!=null && ai.getCreatureState()==CreatureState.IDLE ? CreatureUIState.IDLE
                        : entity.get(AttackTarget.class)!=null ? CreatureUIState.FIGHT : CreatureUIState.BUSY;
                next=new Observation(id,component.creatureId,position==null ? null : Vec3.of(position.position),activity,entity.get(Death.class)!=null);
            }
            observations.put(id,next);
            if (!Objects.equals(old.type(),next.type())) {
                removeFromGroup(old,changedTypes);
                if (next.type()!=null) groups.computeIfAbsent(next.type(),key -> new LinkedHashSet<>()).add(id);
            }
            if (!next.equals(old) && next.type()!=null) changedTypes.add(next.type());
        }
        for (short type:changedTypes) {
            if (type==workerId) continue;
            if (groups.get(type).isEmpty()) cards.remove(Short.valueOf(type));
            else if (!cards.contains(type)) cards.add(type);
        }
    }
    private void removeFromGroup(Observation observation, Set<Short> changedTypes) {
        if (observation.type()!=null) {groups.get(observation.type()).remove(observation.id());changedTypes.add(observation.type());}
    }
    public Observation selectNext(short type, CreatureUIState filter) {
        requireOpen();var group=groups.get(type);
        if (group==null || group.isEmpty()) return null;
        var ids=new ArrayList<>(group);int start=indices.getOrDefault(type,0);
        if (start>=ids.size()) start=0;
        for (int offset=0;offset<ids.size();offset++) {
            int index=(start+offset)%ids.size();var value=observations.get(ids.get(index));
            if (value.selectable() && (filter==null || value.activity()==filter)) {
                indices.put(type,index+1);return value;
            }
        }
        return null;
    }
    public int count(short type, CreatureUIState filter) {
        requireOpen();var group=groups.get(type);if(group==null) return 0;int n=0;
        for(var id:group) {var value=observations.get(id);if(value.selectable() && (filter==null || value.activity()==filter)) n++;}
        return n;
    }
    public List<Short> cards() {requireOpen();return List.copyOf(cards);}
    public short workerId() {return workerId;}
    public CreatureCardsState capture(WorkerAmountControl.State mode, int firstVisible) {
        requireOpen();return new CreatureCardsState(playerId,workerId,List.copyOf(observations.values()),
                groups.entrySet().stream().map(e -> new Group(e.getKey(),List.copyOf(e.getValue()))).toList(),cards,indices,mode,firstVisible);
    }
    public void validate(CreatureCardsState saved, IKwdFile definition) {
        requireOpen();
        if(saved.playerId()!=playerId || saved.workerId()!=workerId || definition.getImp().getCreatureId()!=workerId)
            throw new IllegalArgumentException("Creature cards belong to another player/worker definition");
        for(var group:saved.groups()) if(definition.getCreature(group.type())==null)
            throw new IllegalArgumentException("Missing creature card definition");
    }
    /** The session must bind the corresponding client ECS before ordinary updates resume. */
    public void restore(CreatureCardsState saved, IKwdFile definition) {
        validate(saved,definition);
        var prepared=new HashMap<EntityId,WatchedEntity>();var created=new ArrayList<WatchedEntity>();
        try {
            for(var value:saved.creatures()) {
                var watch=watched.get(value.id());
                if(watch==null) {watch=watch(value.id());created.add(watch);}
                prepared.put(value.id(),watch);
            }
        } catch(RuntimeException failure) {created.forEach(WatchedEntity::release);throw failure;}
        for(var entry:watched.entrySet()) if(!prepared.containsKey(entry.getKey())) entry.getValue().release();
        watched.clear();watched.putAll(prepared);
        observations.clear();for(var value:saved.creatures()) observations.put(value.id(),value);
        groups.clear();for(var group:saved.groups()) groups.put(group.type(),new LinkedHashSet<>(group.ids()));
        cards.clear();cards.addAll(saved.cards());indices.clear();indices.putAll(saved.nextIndices());
    }
    private void requireOpen() {if(closed) throw new IllegalStateException("Creature roster is closed");}
    @Override public void close() {if(!closed) {closed=true;members.release();watched.values().forEach(WatchedEntity::release);watched.clear();}}
}
