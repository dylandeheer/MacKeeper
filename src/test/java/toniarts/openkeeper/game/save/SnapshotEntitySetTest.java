/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.chicken.ChickenState;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotEntitySetTest {
    static class Data extends DefaultEntityData {
        int listeners;
        Data(){setStringIndex(new com.simsilica.es.base.MemStringIndex());}
        @Override public void addEntityComponentListener(EntityComponentListener listener){super.addEntityComponentListener(listener);listeners++;}
        @Override public void removeEntityComponentListener(EntityComponentListener listener){super.removeEntityComponentListener(listener);listeners--;}
    }
    @Test void pendingInspectionDoesNotConsumeChangesAndMatchesTheOrdinaryQuery(){
        var data=new Data();var old=data.createEntity();data.setComponents(old,new ChickenAi(0,ChickenState.PECKING),new ObjectViewState());
        var ordinary=data.getEntities(ChickenAi.class,ObjectViewState.class);var tracked=new SnapshotEntitySet(data,ChickenAi.class,ObjectViewState.class);
        try {
            assertEquals(1,data.listeners);assertEquals(ordinary.getEntityIds(),tracked.getEntityIds());
            data.setComponent(old,new Owner((short)3,(short)3));assertTrue(tracked.pendingChanges(Set.of(old)).isEmpty());
            data.setComponent(old,new ObjectViewState());
            assertEquals(Set.of(old),tracked.pendingChanges(Set.of(old)));assertEquals(Set.of(old),tracked.pendingChanges(Set.of(old)));
            assertTrue(ordinary.applyChanges());assertTrue(tracked.applyChanges());assertEquals(ids(ordinary.getChangedEntities()),ids(tracked.getChangedEntities()));
            assertTrue(tracked.pendingChanges(Set.of(old)).isEmpty());
            data.removeComponent(old,ChickenAi.class);var added=data.createEntity();data.setComponents(added,new ChickenAi(0,ChickenState.WANDERING),new ObjectViewState());
            assertEquals(ordinary.applyChanges(),tracked.applyChanges());assertEquals(ids(ordinary.getAddedEntities()),ids(tracked.getAddedEntities()));assertEquals(ids(ordinary.getRemovedEntities()),ids(tracked.getRemovedEntities()));
            assertEquals(ordinary.getEntityIds(),tracked.getEntityIds());
        } finally {ordinary.release();tracked.release();tracked.release();assertEquals(0,data.listeners);data.close();}
        assertThrows(IllegalStateException.class,()->tracked.pendingChanges(Set.of(old)));
    }
    static Set<EntityId>ids(Set<Entity>entities){var ids=new HashSet<EntityId>();entities.forEach(e->ids.add(e.getId()));return ids;}
}
