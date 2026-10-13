/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.jme3.export.binary.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.session.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientModelSnapshotsTest {
    private IKwdFile definition() {
        var spell=new KeeperSpell();spell.setKeeperSpellId((short)1);
        return (IKwdFile)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IKwdFile.class},(p,m,a)->switch(m.getName()) {
            case "getPlayer" -> null;
            case "getKeeperSpellById" -> ((Number)a[0]).intValue()==1?spell:null;
            default -> throw new AssertionError(m.getName());
        });
    }
    private Keeper keeper(short id) {
        var k=new Keeper(false,id);k.setGold(12345678901L);k.setGoldReserve(14);k.setMaxMana(12345);k.setMana(91);k.setManaGain(7);k.setManaLoose(2);k.setPossession(true);
        var spell=new PlayerSpell((short)1,true);spell.setResearch(23);k.getAvailableSpells().add(spell);k.setCurrentResearch(spell);k.createAlliance((short)4);
        return k;
    }
    private ClientModelState state(Keeper... keepers) {
        return new ClientModelState((short)3,Arrays.stream(keepers).map(Keeper::captureState).toList(),new PresentationQueue.State(List.of(
            new PresentationEvent.Gold((short)3,111,7),new PresentationEvent.Mana((short)3,77,5,8))));
    }
    @Test void restoresObservedValuesAndPendingNotificationsWhileKeepingViewReferences() throws Exception {
        var a=keeper((short)3);var b=keeper((short)4);var saved=state(a,b);var live=new TreeMap<Short,Keeper>(Map.of((short)3,a,(short)4,b));var queue=new PresentationQueue();
        for(int n=0;n<3;n++) {
            a.setGold(14);a.setMana(1);a.setPossession(false);a.getAvailableSpells().getFirst().setResearch(0);b.breakAlliance((short)4);
            var decoded=ClientModelSnapshots.decode(ClientModelSnapshots.encode(saved));decoded.restore(live,queue,definition());
            assertSame(a,live.get((short)3));assertSame(b,live.get((short)4));assertEquals(saved.keepers(),live.values().stream().map(Keeper::captureState).toList());
            assertEquals(saved.pending(),queue.capture());assertSame(a.getCurrentResearch(),a.getAvailableSpells().getFirst());
            var delivered=new ArrayList<PresentationEvent>();queue.drain(()->true,delivered::add);assertEquals(saved.pending().events(),delivered);assertTrue(queue.capture().events().isEmpty());
        }
        a.getCurrentResearch().setResearch(999);assertEquals(23,saved.keepers().getFirst().currentResearch().research());
    }
    @Test void validatesEveryBindingAndPendingResearchBeforeChangingAnyLiveKeeper() {
        var a=keeper((short)3);var b=keeper((short)4);var live=Map.of((short)3,a,(short)4,b);var queue=new PresentationQueue();queue.add(new PresentationEvent.Message(7));
        var before=state(a,b);var pending=queue.capture();b.getAvailableSpells().add(new PlayerSpell((short)255,true));var invalid=state(a,b);b.getAvailableSpells().removeLast();
        assertThrows(IllegalArgumentException.class,()->invalid.restore(live,queue,definition()));assertEquals(before.keepers(),live.values().stream().sorted().map(Keeper::captureState).toList());assertEquals(pending,queue.capture());
        for(short target:new short[]{3,99}) {
            var update=new PresentationEvent.ResearchUpdate(PresentationEvent.ResearchAction.CHANGED,target,new KeeperState.Research(target==3?(short)255:(short)1,ResearchableType.SPELL,true,true,0,false));
            var bad=new ClientModelState((short)3,before.keepers(),new PresentationQueue.State(List.of(update)));
            assertThrows(IllegalArgumentException.class,()->bad.restore(live,queue,definition()));assertEquals(pending,queue.capture());
        }
        assertThrows(IllegalArgumentException.class,()->before.restore(Map.of((short)3,a),queue,definition()));
        queue.close();assertThrows(IllegalStateException.class,()->before.restore(live,queue,definition()));assertEquals(before.keepers(),live.values().stream().sorted().map(Keeper::captureState).toList());
    }
    @Test void currentDispatchCannotReplaceClientValuesOrItsOwnQueue() {
        var a=keeper((short)3);var saved=state(a);a.setMana(2);var queue=new PresentationQueue();queue.add(new PresentationEvent.Message(1));
        queue.drain(()->true,event->assertThrows(IllegalStateException.class,()->saved.restore(Map.of((short)3,a),queue,definition())));
        assertEquals(2,a.getMana());assertTrue(queue.capture().events().isEmpty());
    }
    @Test void localHandoffClonesAllValuesSeparatelyForEveryListener() {
        var session=new LocalGameSession();var source=keeper((short)3);var received=new ArrayList<Keeper>();
        GameSessionListener listener=(GameSessionListener)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{GameSessionListener.class},(p,m,a)->{
            if(m.getName().equals("onGameDataLoaded")){received.add(((Collection<Keeper>)a[0]).iterator().next());return null;}
            if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);throw new AssertionError(m.getName());
        });
        session.addGameSessionListener(listener);
        GameSessionListener second=(GameSessionListener)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{GameSessionListener.class},Proxy.getInvocationHandler(listener));
        session.addGameSessionListener(second);session.sendGameData(List.of(source));assertEquals(2,received.size());
        for(var value:received){assertNotSame(source,value);assertEquals(source.captureState(),value.captureState());assertSame(value.getAvailableSpells().getFirst(),value.getCurrentResearch());}
        received.getFirst().getCurrentResearch().setResearch(99);assertEquals(23,source.getCurrentResearch().getResearch());assertEquals(23,received.getLast().getCurrentResearch().getResearch());
        session.getEntityData().close();
    }
    @Test void legacyNetworkSerializationAlsoRetainsPossession() throws Exception {
        var source=keeper((short)3);var bytes=new ByteArrayOutputStream();BinaryExporter.getInstance().save(source,bytes);
        Keeper copy=(Keeper)BinaryImporter.getInstance().load(bytes.toByteArray());assertTrue(copy.isPossession());assertEquals(source.captureState(),copy.captureState());
        var empty=new Keeper(false,(short)3);bytes.reset();BinaryExporter.getInstance().save(empty,bytes);copy=(Keeper)BinaryImporter.getInstance().load(bytes.toByteArray());assertFalse(copy.isPossession());
    }
    @Test void codecAndRosterRejectMalformedContainers() throws Exception {
        var a=keeper((short)3);var b=keeper((short)4);var state=state(a,b);byte[] bytes=ClientModelSnapshots.encode(state);
        assertEquals(state,ClientModelSnapshots.decode(bytes));
        for(int n=0;n<bytes.length;n++){final int size=n;assertThrows(IOException.class,()->ClientModelSnapshots.decode(Arrays.copyOf(bytes,size)));}
        assertThrows(IOException.class,()->ClientModelSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        for(int offset:new int[]{0,4,10,14}){var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->ClientModelSnapshots.decode(bad));}
        assertThrows(IllegalArgumentException.class,()->state(b,a));assertThrows(IllegalArgumentException.class,()->state(a,a));assertThrows(IllegalArgumentException.class,()->state(b));
        assertThrows(IllegalArgumentException.class,()->a.restoreValues(b.captureState(),null));assertEquals(state.keepers().getFirst(),a.captureState());
    }
}
