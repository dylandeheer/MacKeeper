/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.component.ViewType;
import toniarts.openkeeper.tools.convert.map.IKwdFile;

/** Last-observed hand queue, independent of the authoritative server hand. */
public record HandViewState(short playerId, int capacity, boolean enabled, boolean visible, List<Item> items) {
    public static final int MAX_ITEMS = 65_536;
    public HandViewState {
        items=List.copyOf(items);
        if(playerId<0 || playerId>255 || capacity<0 || capacity>MAX_ITEMS || items.size()>MAX_ITEMS)
            throw new IllegalArgumentException("Invalid hand view identity/capacity");
        var ids=new HashSet<EntityId>();int previous=-1;
        for(var item:items) {
            if(!ids.add(item.entityId()) || item.index()<previous || item.index()>=capacity)
                throw new IllegalArgumentException("Invalid hand view order/index");
            previous=item.index();
        }
    }
    public record Item(EntityId entityId, int index, ViewType type, short definitionId) {
        public Item {
            if(entityId==null || entityId.getId()<0 || index<0 || index>=MAX_ITEMS
                    || (type!=ViewType.CREATURE && type!=ViewType.OBJECT) || definitionId<0 || definitionId>255)
                throw new IllegalArgumentException("Invalid observed hand item");
        }
    }
    public void validateDefinitions(IKwdFile definition) {
        for(var item:items) {
            if(item.type()==ViewType.CREATURE ? definition.getCreature(item.definitionId())==null : definition.getObject(item.definitionId())==null)
                throw new IllegalArgumentException("Missing held item definition");
        }
    }
}
