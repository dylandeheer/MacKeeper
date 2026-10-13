/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import static org.junit.jupiter.api.Assertions.*;

class EntitySnapshotsTest {
    @Test void repeatedSectionRoundTripsRetainIdsFieldsReferencesAndNextAllocation() throws Exception {
        var original = new GameEntityData();
        try {
            EntityId first=original.createEntity(), second=original.createEntity();original.createEntity();
            original.setComponent(first,new CreatureSpells(new ArrayList<>(List.of(second))));
            original.setComponent(second,new Health(20,50));
            byte[] encoded=EntitySnapshots.encode(original.capture());
            for(int i=0;i<4;i++) {
                var restored=GameEntityData.restore(EntitySnapshots.decode(encoded));
                try {
                    assertEquals(20,restored.getComponent(second,Health.class).health);
                    assertEquals(List.of(second),restored.getComponent(first,CreatureSpells.class).creatureSpells);
                    assertArrayEquals(encoded,EntitySnapshots.encode(restored.capture()));
                    assertEquals(3,restored.createEntity().getId());
                } finally {restored.close();}
                encoded=EntitySnapshots.encode(EntitySnapshots.decode(encoded));
            }
            assertEquals(3,original.capture().nextEntityId());
        } finally {original.close();}
    }

    @Test void rejectsEveryTruncationAndUnknownHeadersWithoutModifyingAnExistingStore() throws Exception {
        var current=new GameEntityData();
        try {
            EntityId id=current.createEntity();current.setComponent(id,new Health(10,20));
            byte[] valid=EntitySnapshots.encode(current.capture());
            for(int length=0;length<valid.length;length++) {
                byte[] truncated=Arrays.copyOf(valid,length);assertThrows(IOException.class,()->EntitySnapshots.decode(truncated));
            }
            byte[] version=valid.clone();version[7]=2;assertThrows(IOException.class,()->EntitySnapshots.decode(version));
            byte[] magic=valid.clone();magic[0]=0;assertThrows(IOException.class,()->EntitySnapshots.decode(magic));
            assertThrows(IOException.class,()->EntitySnapshots.decode(Arrays.copyOf(valid,valid.length+1)));
            assertEquals(10,current.getComponent(id,Health.class).health);assertArrayEquals(valid,EntitySnapshots.encode(current.capture()));
        } finally {current.close();}
    }

    @Test void rejectsDuplicateAndOutOfRangeEntityIdsComponentsAndReferences() throws Exception {
        assertBad(1,2,out->{out.writeLong(0);out.writeShort(0);out.writeLong(0);out.writeShort(0);});
        assertBad(1,1,out->{out.writeLong(1);out.writeShort(0);});
        assertBad(1,1,out->{out.writeLong(-1);out.writeShort(0);});
        byte[] health=ComponentSnapshots.encode(ComponentSnapshots.capture(new Health(1,2)));
        assertBad(1,1,out->{out.writeLong(0);out.writeShort(2);component(out,health);component(out,health);});
        var target=new AttackTarget();target.entityId=new EntityId(2);
        byte[] reference=ComponentSnapshots.encode(ComponentSnapshots.capture(target));
        assertBad(1,1,out->{out.writeLong(0);out.writeShort(1);component(out,reference);});
    }

    @Test void rejectsUnboundedOrImpossibleLengthsBeforeAllocatingPayload() throws Exception {
        assertBad(-1,0,out->{});
        for(int count:new int[]{-1,GameEntityData.MAX_ENTITIES+1,Integer.MAX_VALUE})assertBad(1,count,out->{});
        assertBad(1,1000,out->{});
        assertBad(1,1,out->{out.writeLong(0);out.writeShort(66);});
        for(int length:new int[]{-1,ComponentSnapshots.MAX_COMPONENT_BYTES+1,1_000_000})
            assertBad(1,1,out->{out.writeLong(0);out.writeShort(1);out.writeInt(length);});
    }

    @Test void emptyWorldAndExhaustedAllocatorAreValidAndImmutable() throws Exception {
        List<GameEntityData.EntityState> entities=new ArrayList<>();
        var snapshot=new GameEntityData.Snapshot(Long.MAX_VALUE,entities);entities.add(new GameEntityData.EntityState(0,List.of()));
        var decoded=EntitySnapshots.decode(EntitySnapshots.encode(snapshot));
        assertTrue(decoded.entities().isEmpty());assertEquals(Long.MAX_VALUE,decoded.nextEntityId());
        assertThrows(UnsupportedOperationException.class,()->decoded.entities().add(new GameEntityData.EntityState(0,List.of())));
    }

    static void assertBad(long next,int count,ComponentSnapshotsTest.Payload payload) throws Exception {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b4553);out.writeInt(1);out.writeLong(next);out.writeInt(count);payload.write(out);
        }assertThrows(IOException.class,()->EntitySnapshots.decode(bytes.toByteArray()));
    }
    static void component(DataOutputStream out,byte[] bytes) throws IOException {out.writeInt(bytes.length);out.write(bytes);}
}
