/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import com.simsilica.es.*;
import com.simsilica.es.filter.FieldFilter;
import java.util.*;
import toniarts.openkeeper.game.component.InHand;

/** A query can advance while visual staging fails; observations publish only with the prepared view. */
public final class HandViewQueue implements AutoCloseable {
    private final EntitySet query;
    private final short playerId;
    private final int capacity;
    private List<HandViewState.Item> observed=List.of();
    private boolean closed;
    public HandViewQueue(EntityData data, short playerId, int capacity) {
        new HandViewState(playerId,capacity,true,false,List.of());
        this.playerId=playerId;this.capacity=capacity;
        query=data.getEntities(new FieldFilter(InHand.class,"playerId",playerId),InHand.class);
    }
    /** Detached current proposal; it does not replace the displayed observations. */
    public List<HandViewState.Item> current() {
        requireOpen();query.applyChanges();var current=new HashMap<EntityId,HandViewState.Item>();
        for(var entity:query) {
            var value=entity.get(InHand.class);
            current.put(entity.getId(),new HandViewState.Item(entity.getId(),value.index,value.type,value.id));
        }
        var result=new ArrayList<HandViewState.Item>();
        // Keep stable tie order among retained members, then append new identities deterministically.
        for(var old:observed) {var next=current.remove(old.entityId());if(next!=null) result.add(next);}
        current.values().stream().sorted(Comparator.comparing(HandViewState.Item::entityId)).forEach(result::add);
        result.sort(Comparator.comparingInt(HandViewState.Item::index));
        return new HandViewState(playerId,capacity,true,false,result).items();
    }
    public List<HandViewState.Item> observed() {requireOpen();return observed;}
    public void publish(List<HandViewState.Item> items) {
        requireOpen();observed=new HandViewState(playerId,capacity,true,false,items).items();
    }
    private void requireOpen() {if(closed) throw new IllegalStateException("Hand view query is closed");}
    @Override public void close() {if(!closed) {closed=true;query.release();}}
}
