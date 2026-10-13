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
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.save.CreatureCardSnapshots;
import toniarts.openkeeper.gui.nifty.AbstractCreatureCardControl.CreatureUIState;
import toniarts.openkeeper.gui.nifty.WorkerAmountControl.State;
import toniarts.openkeeper.utils.WorldUtils;
import toniarts.openkeeper.view.creature.*;
import toniarts.openkeeper.view.creature.CreatureCardsState.*;
import static org.junit.jupiter.api.Assertions.*;

class CreatureCardSnapshotsTest {
    @Test void repeatedLoadsKeepNextClickAndPendingTransfersPositionsTypesAndRemovals() throws Exception {
        try(var f=new Fixture()) {
            EntityId a=f.add(3,2,2), b=f.add(3,2,4), c=f.add(3,3,6), worker=f.add(3,1,8);
            try(var source=f.roster()) {
                source.update();assertEquals(a,source.selectNext((short)2,null).id());
                // Changes are pending: the displayed observations must remain unchanged during restoration.
                f.data.setComponent(a,new Owner((short)4,(short)4));
                f.data.setComponent(b,new Position(0,WorldUtils.pointToVector3f(19,19)));
                var type=new CreatureComponent();type.creatureId=2;f.data.setComponent(c,type);
                f.data.removeEntity(worker);f.add(3,4,12);
                for(int load=0;load<3;load++) try(var copy=f.roster()) {
                    var saved=roundTrip(capture(source));copy.restore(saved,f.definition);assertEquals(saved,capture(copy));
                    // Several clicks before the normal refresh must see the same old values and cycle positions.
                    for(int click=0;click<7;click++) assertEquals(source.selectNext((short)2,null),copy.selectNext((short)2,null));
                    source.update();copy.update();assertEquals(capture(source),capture(copy));
                    assertEquals(List.of((short)2,(short)4),source.cards());
                    assertEquals(2,copy.count((short)2,null));assertEquals(0,copy.count((short)1,null));
                    assertEquals(source.selectNext((short)2,null),copy.selectNext((short)2,null));
                }
            }
        }
    }

    @Test void captureBeforeFirstRefreshRetainsPendingMembersAndEmptyCards() throws Exception {
        try(var f=new Fixture()) {
            f.add(3,2,3);
            try(var source=f.roster();var copy=f.roster()) {
                var saved=roundTrip(capture(source));assertTrue(saved.cards().isEmpty());assertNull(saved.creatures().getFirst().type());
                copy.restore(saved,f.definition);assertEquals(saved,capture(copy));assertNull(copy.selectNext((short)2,null));
                source.update();copy.update();assertEquals(capture(source),capture(copy));assertEquals(1,copy.count((short)2,null));
            }
        }
    }

    @Test void emptySpeciesAndMissingFiltersAreNoOpsAndDeadHeldCreaturesAreExcluded() throws Exception {
        try(var f=new Fixture();var roster=f.roster()) {
            assertNull(roster.selectNext((short)1,null));assertNull(roster.selectNext((short)250,null));
            EntityId a=f.add(3,1,1),b=f.add(3,1,2),c=f.add(3,1,3),dead=f.add(3,1,4),held=f.add(3,1,5);
            f.data.setComponent(a,new CreatureAi(0,CreatureState.IDLE,(short)1));
            f.data.setComponent(b,new AttackTarget(a));f.data.setComponent(dead,new Death(0));f.data.removeComponent(held,Position.class);
            roster.update();assertEquals(3,roster.count((short)1,null));
            assertEquals(a,roster.selectNext((short)1,CreatureUIState.IDLE).id());
            assertEquals(b,roster.selectNext((short)1,CreatureUIState.FIGHT).id());
            assertEquals(c,roster.selectNext((short)1,CreatureUIState.BUSY).id());
            var before=capture(roster);assertNull(roster.selectNext((short)1,CreatureUIState.ANGRY));assertEquals(before,capture(roster));
            for(var id:List.of(a,b,c,dead,held)) f.data.removeEntity(id);
            roster.update();assertNull(roster.selectNext((short)1,null));assertEquals(0,roster.count((short)1,null));
            f.add(3,1,8);roster.update();assertNotNull(roster.selectNext((short)1,null));
        }
    }

    @Test void reorderedSpeciesAndCardOrderRemainDistinctAndClosedOwnersRejectUse() throws Exception {
        try(var f=new Fixture();var roster=f.roster()) {
            EntityId a=f.add(3,2,1);f.add(3,3,2);roster.update();
            f.data.removeEntity(a);roster.update();f.add(3,2,3);roster.update();
            var saved=capture(roster);assertEquals(List.of((short)2,(short)3),saved.groups().stream().map(Group::type).toList());
            assertEquals(List.of((short)3,(short)2),saved.cards());
            try(var copy=f.roster()) {copy.restore(roundTrip(saved),f.definition);assertEquals(saved,capture(copy));}
            roster.close();assertThrows(IllegalStateException.class,()->capture(roster));assertThrows(IllegalStateException.class,roster::update);
        }
    }

    @Test void bindingRejectsWrongPlayerWorkerAndDefinitionsWithoutChangingObservations() throws Exception {
        try(var f=new Fixture();var roster=f.roster()) {
            f.add(3,2,4);roster.update();var saved=capture(roster);
            for(var bad:List.of(new CreatureCardsState((short)4,saved.workerId(),saved.creatures(),saved.groups(),saved.cards(),saved.nextIndices(),saved.mode(),0),
                    new CreatureCardsState(saved.playerId(),(short)5,saved.creatures(),saved.groups(),saved.cards(),saved.nextIndices(),saved.mode(),0))) {
                assertThrows(IllegalArgumentException.class,()->roster.restore(bad,f.definition));assertEquals(saved,capture(roster));
            }
            f.types.remove((short)2);assertThrows(IllegalArgumentException.class,()->roster.restore(saved,f.definition));assertEquals(saved,capture(roster));
        }
    }

    @Test void codecRoundTripsModesDetachedValuesAndRejectsEveryTruncationAndMalformedState() throws Exception {
        try(var f=new Fixture();var roster=f.roster()) {
            f.add(3,2,4);f.add(3,3,8);roster.update();roster.selectNext((short)2,null);
            for(var mode:State.values()) {
                var saved=roster.capture(mode,1);byte[] bytes=CreatureCardSnapshots.encode(saved);
                assertEquals(saved,CreatureCardSnapshots.decode(bytes));assertArrayEquals(bytes,CreatureCardSnapshots.encode(CreatureCardSnapshots.decode(bytes)));
                for(int n=0;n<bytes.length;n++) {final int size=n;assertThrows(IOException.class,()->CreatureCardSnapshots.decode(Arrays.copyOf(bytes,size)));}
                assertThrows(IOException.class,()->CreatureCardSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            }
            var saved=capture(roster);assertThrows(UnsupportedOperationException.class,()->saved.cards().clear());
            assertThrows(IllegalArgumentException.class,()->new CreatureCardsState(saved.playerId(),saved.workerId(),saved.creatures(),saved.groups(),List.of((short)2,(short)2),saved.nextIndices(),saved.mode(),0));
            assertThrows(IllegalArgumentException.class,()->new CreatureCardsState(saved.playerId(),saved.workerId(),saved.creatures(),List.of(),saved.cards(),saved.nextIndices(),saved.mode(),0));
            assertThrows(IllegalArgumentException.class,()->new CreatureCardsState(saved.playerId(),saved.workerId(),saved.creatures(),saved.groups(),saved.cards(),Map.of((short)2,-1),saved.mode(),0));
            byte[] bytes=CreatureCardSnapshots.encode(saved);
            for(int offset:new int[]{0,4,12}) {byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->CreatureCardSnapshots.decode(bad));}
            byte[] bad=bytes.clone();bad[24]=2;assertThrows(IOException.class,()->CreatureCardSnapshots.decode(bad));
            assertThrows(IOException.class,()->CreatureCardSnapshots.decode(new byte[CreatureCardSnapshots.MAX_BYTES+1]));
        }
    }

    static CreatureCardsState capture(CreatureCardRoster roster){return roster.capture(State.TOTAL,0);}
    static CreatureCardsState roundTrip(CreatureCardsState state)throws IOException{return CreatureCardSnapshots.decode(CreatureCardSnapshots.encode(state));}
    static final class Fixture implements AutoCloseable {
        final DefaultEntityData data=new DefaultEntityData();final Map<Short,Creature> types=new HashMap<>();final IKwdFile definition;
        Fixture() {
            for(short i=1;i<=5;i++){var c=new Creature();c.setCreatureId(i);types.put(i,c);}
            definition=(IKwdFile)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IKwdFile.class},(p,m,a)->switch(m.getName()) {
                case "getCreature"->types.get((short)a[0]);case "getImp"->types.get((short)1);default->throw new AssertionError(m.getName());
            });
        }
        CreatureCardRoster roster(){return new CreatureCardRoster(data,(short)3,(short)1);}
        EntityId add(int owner,int type,int x) {
            var id=data.createEntity();var c=new CreatureComponent();c.creatureId=(short)type;
            data.setComponents(id,c,new Health(100,100),new Owner((short)owner,(short)owner),new Position(0,WorldUtils.pointToVector3f(x,x)));return id;
        }
        public void close(){data.close();}
    }
}
