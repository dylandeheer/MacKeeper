/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.save.HandViewSnapshots;
import toniarts.openkeeper.view.*;
import static org.junit.jupiter.api.Assertions.*;

class HandViewSnapshotsTest {
    @Test void repeatedRestoresKeepObservedDropOrderUntilPendingChangesArePublished() throws Exception {
        var data=new DefaultEntityData();
        try(var source=new HandViewQueue(data,(short)3,20)) {
            var a=add(data,3,19,ViewType.CREATURE,1);var b=add(data,3,18,ViewType.OBJECT,2);
            source.publish(source.current());assertEquals(List.of(b,a),source.observed().stream().map(HandViewState.Item::entityId).toList());
            // Existing model descriptors remain as observed, even after the ECS moved on.
            data.removeComponent(b,InHand.class);data.setComponent(a,new InHand(17,(short)3,ViewType.OBJECT,(short)2));
            var c=add(data,3,16,ViewType.CREATURE,1);add(data,4,0,ViewType.OBJECT,2);
            for(int i=0;i<3;i++) try(var copy=new HandViewQueue(data,(short)3,20)) {
                var saved=roundTrip(new HandViewState((short)3,20,true,true,source.observed()));copy.publish(saved.items());
                assertEquals(saved.items(),copy.observed());assertEquals(source.current(),copy.current());
                assertEquals(saved.items(),source.observed());assertEquals(saved.items(),copy.observed());
                var next=source.current();source.publish(next);copy.publish(copy.current());assertEquals(source.observed(),copy.observed());
                assertEquals(List.of(c,a),copy.observed().stream().map(HandViewState.Item::entityId).toList());
            }
        } finally {data.close();}
    }
    @Test void failedVisualStagingCanRetryTheSameAlreadyAdvancedQueryAndTiesKeepOrder() {
        var data=new DefaultEntityData();
        try(var queue=new HandViewQueue(data,(short)3,4)) {
            var a=add(data,3,1,ViewType.CREATURE,1);var b=add(data,3,1,ViewType.CREATURE,1);
            var current=queue.current();assertTrue(queue.observed().isEmpty());assertEquals(current,queue.current());
            queue.publish(List.of(current.get(1),current.get(0)));assertEquals(List.of(b,a),queue.current().stream().map(HandViewState.Item::entityId).toList());
            data.setComponent(a,new InHand(0,(short)3,ViewType.CREATURE,(short)1));
            var proposed=queue.current();assertEquals(b,queue.observed().getFirst().entityId());
            assertEquals(proposed,queue.current());queue.publish(proposed);assertEquals(a,queue.observed().getFirst().entityId());
            data.setComponent(a,new InHand(0,(short)4,ViewType.CREATURE,(short)1));queue.publish(queue.current());assertEquals(List.of(b),queue.observed().stream().map(HandViewState.Item::entityId).toList());
            queue.close();assertThrows(IllegalStateException.class,queue::current);assertThrows(IllegalStateException.class,queue::observed);
        } finally {data.close();}
    }
    @Test void descriptorsAreDetachedAndValidatePlayerCapacityOrderAndDefinitions() {
        var creature=new Creature();var object=new GameObject();
        var definition=(IKwdFile)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IKwdFile.class},(p,m,a)->switch(m.getName()) {
            case "getCreature"->(short)a[0]==1?creature:null;case "getObject"->((Number)a[0]).intValue()==2?object:null;default->throw new AssertionError(m.getName());
        });
        var a=new HandViewState.Item(new EntityId(0),0,ViewType.CREATURE,(short)1);
        var b=new HandViewState.Item(new EntityId(1),1,ViewType.OBJECT,(short)2);
        var list=new ArrayList<>(List.of(a,b));var saved=new HandViewState((short)3,2,false,false,list);list.clear();
        assertEquals(2,saved.items().size());saved.validateDefinitions(definition);assertThrows(UnsupportedOperationException.class,()->saved.items().clear());
        assertThrows(IllegalArgumentException.class,()->new HandViewState((short)3,2,true,false,List.of(b,a)));
        assertThrows(IllegalArgumentException.class,()->new HandViewState((short)3,1,true,false,List.of(a,b)));
        assertThrows(IllegalArgumentException.class,()->new HandViewState((short)3,2,true,false,List.of(a,a)));
        assertThrows(IllegalArgumentException.class,()->new HandViewState.Item(new EntityId(0),0,ViewType.DOOR,(short)1));
        var missing=new HandViewState((short)3,1,true,false,List.of(new HandViewState.Item(new EntityId(0),0,ViewType.CREATURE,(short)255)));
        assertThrows(IllegalArgumentException.class,()->missing.validateDefinitions(definition));
    }
    @Test void codecRetainsEmptyAndMixedQueuesWithBothFlagsAndRejectsMalformedSections() throws Exception {
        var items=List.of(new HandViewState.Item(new EntityId(0),0,ViewType.CREATURE,(short)1),
                new HandViewState.Item(new EntityId(Long.MAX_VALUE),19,ViewType.OBJECT,(short)2));
        for(boolean enabled:List.of(false,true)) for(boolean visible:List.of(false,true)) {
            var state=new HandViewState((short)255,20,enabled,visible,items);byte[] bytes=HandViewSnapshots.encode(state);
            assertEquals(state,HandViewSnapshots.decode(bytes));assertArrayEquals(bytes,HandViewSnapshots.encode(HandViewSnapshots.decode(bytes)));
            for(int n=0;n<bytes.length;n++) {final int size=n;assertThrows(IOException.class,()->HandViewSnapshots.decode(Arrays.copyOf(bytes,size)));}
            assertThrows(IOException.class,()->HandViewSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        }
        var empty=new HandViewState((short)0,0,false,false,List.of());assertEquals(empty,roundTrip(empty));
        byte[] bytes=HandViewSnapshots.encode(empty);
        for(int offset:new int[]{0,4,10,16}) {byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->HandViewSnapshots.decode(bad));}
        for(int offset:new int[]{14,15}) {byte[] bad=bytes.clone();bad[offset]=2;assertThrows(IOException.class,()->HandViewSnapshots.decode(bad));}
        assertThrows(IOException.class,()->HandViewSnapshots.decode(new byte[HandViewSnapshots.MAX_BYTES+1]));
    }
    private static HandViewState roundTrip(HandViewState state)throws IOException{return HandViewSnapshots.decode(HandViewSnapshots.encode(state));}
    private static EntityId add(DefaultEntityData data,int player,int index,ViewType type,int definition) {
        var id=data.createEntity();data.setComponent(id,new InHand(index,(short)player,type,(short)definition));return id;
    }
}
