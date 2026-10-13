/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import static org.junit.jupiter.api.Assertions.*;

class GameEntityDataTest {
    @Test void restoreRetainsZeroEmptyAndDeletedIdsWithoutReusingHighWaterMark() throws Exception {
        var source = new GameEntityData();
        try {
            EntityId zero=source.createEntity(), empty=source.createEntity(), removed=source.createEntity();
            assertEquals(0,zero.getId());source.setComponent(zero,new Health(80,100));
            source.removeEntity(removed);
            GameEntityData.Snapshot snapshot=source.capture();
            assertEquals(3,snapshot.nextEntityId());assertEquals(List.of(0L,1L),snapshot.entities().stream().map(GameEntityData.EntityState::id).toList());
            var restored=GameEntityData.restore(snapshot);
            try {
                assertEquals(3,restored.createEntity().getId());assertEquals(80,restored.getComponent(zero,Health.class).health);
                assertTrue(restored.capture().entities().stream().anyMatch(e->e.id()==empty.getId()&&e.components().isEmpty()));
                source.getComponent(zero,Health.class).health=1;
                restored.getComponent(zero,Health.class).health=2;
                var second=GameEntityData.restore(snapshot);
                try {assertEquals(80,second.getComponent(zero,Health.class).health);} finally {second.close();}
            } finally {restored.close();}
            assertNotNull(source.getStrings());int textId=source.getStrings().getStringId("independent index",true);
            assertEquals("independent index",source.getStrings().getString(textId));
        } finally {source.close();}
    }

    @Test void preservesReferencesToRemovedTargetsButRejectsNeverAllocatedReferences() throws Exception {
        var data=new GameEntityData();
        try {
            EntityId entity=data.createEntity(), target=data.createEntity();
            var attack=new AttackTarget();attack.entityId=target;data.setComponent(entity,attack);data.removeEntity(target);
            var snapshot=data.capture();var restored=GameEntityData.restore(snapshot);
            try {assertEquals(target,restored.getComponent(entity,AttackTarget.class).entityId);assertEquals(2,restored.createEntity().getId());}
            finally {restored.close();}
            attack.entityId=new EntityId(100);assertThrows(IOException.class,data::capture);
        } finally {data.close();}
    }

    @Test void rejectsDuplicateEntitiesComponentsAndCollidingAllocatorBeforeRestore() throws Exception {
        var health=ComponentSnapshots.capture(new Health(1,2));
        assertThrows(IllegalArgumentException.class,()->new GameEntityData.EntityState(0,List.of(health,health)));
        var entity=new GameEntityData.EntityState(0,List.of(health));
        assertThrows(IllegalArgumentException.class,()->new GameEntityData.Snapshot(1,List.of(entity,entity)));
        assertThrows(IllegalArgumentException.class,()->new GameEntityData.Snapshot(0,List.of(entity)));
        assertThrows(IllegalArgumentException.class,()->new GameEntityData.Snapshot(-1,List.of()));
        assertThrows(IllegalArgumentException.class,()->new GameEntityData.EntityState(-1,List.of()));
    }

    @Test void includesExplicitIdsAndRemovedComponentsAndRejectsUnknownLiveTypes() throws Exception {
        var data=new GameEntityData();
        try {
            EntityId id=new EntityId(90);data.setComponents(id,new Health(1,2),new Gold(3,4));
            assertEquals(91,data.createEntity().getId());data.removeComponent(id,Gold.class);
            assertEquals(List.of("health"),data.capture().entities().getFirst().components().stream().map(ComponentSnapshots.Snapshot::type).toList());
            var unknown=new EntityComponent(){};data.setComponent(id,unknown);assertThrows(IOException.class,data::capture);
            data.removeComponent(id,unknown.getClass());assertDoesNotThrow(data::capture);
        } finally {data.close();}
    }

    @Test void allocatorIsUniqueAcrossWorkersAndCannotWrapAtExhaustion() throws Exception {
        var data=new GameEntityData();
        try(var workers=Executors.newFixedThreadPool(4)) {
            List<Future<Set<EntityId>>> futures=new ArrayList<>();
            for(int i=0;i<4;i++)futures.add(workers.submit(()->{Set<EntityId> ids=new HashSet<>();for(int j=0;j<1000;j++)ids.add(data.createEntity());return ids;}));
            Set<EntityId> all=new HashSet<>();for(var future:futures){Set<EntityId> batch=future.get();assertEquals(1000,batch.size());for(var id:batch)assertTrue(all.add(id));}
            assertEquals(4000,data.capture().nextEntityId());assertEquals(4000,data.capture().entities().size());
        } finally {data.close();}
        var exhausted=GameEntityData.restore(new GameEntityData.Snapshot(Long.MAX_VALUE,List.of()));
        try {assertThrows(IllegalStateException.class,exhausted::createEntity);assertEquals(Long.MAX_VALUE,exhausted.capture().nextEntityId());}
        finally {exhausted.close();}
    }
}
