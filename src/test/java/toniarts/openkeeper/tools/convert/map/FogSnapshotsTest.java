/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.fogofwar.*;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.game.save.FogSnapshots;
import toniarts.openkeeper.utils.*;
import toniarts.openkeeper.view.fogofwar.*;
import static org.junit.jupiter.api.Assertions.*;

class FogSnapshotsTest {
    @Test void repeatedIndependentRestoresContinueHeartPhasesAndPendingMembershipWithoutReplay() throws Exception {
        try (var f = new Fixture()) {
            var old = f.heart(3, 4, 4);var source = f.owner();
            // Existing initial query members are already observed, matching the normal query contract.
            source.update(0);assertTrue(source.captureState().hearts().isEmpty());
            f.data.removeEntity(old);
            f.heart(3, 6, 6);source.update(.15f);
            for (int i = 0; i < 53; i++) source.update(.15f);
            assertEquals(54, source.captureState().hearts().getFirst().elapsedIntervals());
            f.heart(3, 12, 12);f.heart(4, 15, 15); // not observed yet
            for (int restore = 0; restore < 3; restore++) {
                var saved = roundTrip(source.captureState());var target = f.owner();
                f.events.clear();target.restoreState(saved);assertTrue(f.events.isEmpty());
                assertEquals(saved, target.captureState());
                var second = f.owner();second.restoreState(roundTrip(target.captureState()));
                // All instances see the same current ECS, but independently keep pre-query observations.
                for (int i = 0; i < 65; i++) {
                    f.events.clear();source.update(.15f);var expected = List.copyOf(f.events);
                    f.events.clear();target.update(.15f);assertEquals(expected, f.events);
                    f.events.clear();second.update(.15f);assertEquals(expected, f.events);
                    assertEquals(source.captureState(), target.captureState());assertEquals(source.captureState(), second.captureState());
                }
                assertTrue(source.captureState().hearts().isEmpty());
                target.stop();second.stop();
            }
            source.stop();
        }
    }

    @Test void restoresDirtyHistoryEnemyDedupTagsPossessionAndDerivedDoorCache() throws Exception {
        try (var f = new Fixture()) {
            var source = f.owner();var own = f.creature(3, 4, 4);var enemy = f.creature(4, 5, 4);
            source.update(.15f);assertTrue(f.events.contains("enemy:" + enemy));
            var door = f.data.createEntity();f.data.setComponents(door, new Position(0, WorldUtils.pointToVector3f(4, 4)),
                    new DoorComponent((short)1, false, false), new DoorViewState((short)1, false, false, false));
            source.update(.05f); // door cache still old until vision interval
            source.markPendingTagged(List.of(new Point(18,18)), true);
            source.revealActionPointTiles(List.of(new Point(17,17)), true); // still dirty at capture
            var saved = roundTrip(source.captureState());var target = f.owner();f.events.clear();target.restoreState(saved);
            assertTrue(f.events.isEmpty());assertEquals(saved, target.captureState());assertTrue(target.isPendingTagged(new Point(18,18)));
            target.refreshPresentation();assertEquals(saved, target.captureState());assertEquals(1, f.events.size());
            for (int i = 0; i < 3; i++) {
                f.events.clear();source.update(.1f);var expected = List.copyOf(f.events);
                f.events.clear();target.update(.1f);assertEquals(expected, f.events);assertEquals(source.captureState(), target.captureState());
                assertTrue(f.events.stream().noneMatch(e -> e.startsWith("enemy:")));
            }
            f.data.setComponent(own, new Position(0, WorldUtils.pointToVector3f(16,16)));
            f.data.setComponent(door, new DoorViewState((short)1, false, false, true));
            source.update(.15f);target.update(.15f);assertEquals(source.captureState(), target.captureState());
            source.setPossessedCreature(own);saved = roundTrip(source.captureState());target.restoreState(saved);
            assertTrue(target.isVisible(new Point(0,0)));assertEquals(saved, target.captureState());
            source.disableFogOfWar();saved = roundTrip(source.captureState());target.restoreState(saved);
            assertEquals(saved, target.captureState());source.stop();target.stop();
        }
    }

    @Test void subintervalClockAndLargeFrameRemainderSurviveAndInvalidBindingIsAtomic() throws Exception {
        try (var f = new Fixture()) {
            var source = f.owner();source.update(1f);var saved = roundTrip(source.captureState());
            assertTrue(saved.visionTime() > .15f);
            var target = f.owner();target.restoreState(saved);
            for (float delta : new float[]{0, .001f, .07f, .5f, .2f}) {
                source.update(delta);target.update(delta);assertEquals(source.captureState(), target.captureState());
            }
            saved = target.captureState();var k = saved.knowledge();
            var bad = new FogSnapshot(new FogState.Snapshot((short)4, k.width(), k.height(), k.enabled(), k.explored(), k.perceived(), k.dirty()),
                    saved.visionTime(), saved.hearts(), saved.pendingTags(), saved.seenEnemies(), saved.observedHearts(), saved.possessedCreature());
            f.events.clear();assertThrows(IllegalArgumentException.class, () -> target.restoreState(bad));
            assertEquals(saved, target.captureState());assertTrue(f.events.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> target.update(Float.NaN));
            assertEquals(saved, target.captureState());source.stop();target.stop();
            assertThrows(IllegalStateException.class, target::captureState);
        }
    }

    @Test void pendingFirstSightingSurvivesRedrawButPreviouslyReportedEnemyDoesNotRepeat() throws Exception {
        try (var f = new Fixture()) {
            var source = f.owner();var enemy = f.creature(4,10,10);
            source.revealActionPointTiles(List.of(new Point(10,10)),true);
            var target = f.owner();target.restoreState(roundTrip(source.captureState()));
            f.events.clear();target.refreshPresentation();assertTrue(f.events.stream().noneMatch(e -> e.startsWith("enemy:")));
            f.events.clear();source.update(.15f);var expected=List.copyOf(f.events);
            f.events.clear();target.update(.15f);assertEquals(expected,f.events);assertTrue(f.events.contains("enemy:"+enemy));
            assertEquals(source.captureState(),target.captureState());
            // Conceal/reveal after a load must not alert for this entity again.
            target.restoreState(roundTrip(target.captureState()));
            target.revealActionPointTiles(List.of(new Point(10,10)),false);target.update(.15f);
            target.revealActionPointTiles(List.of(new Point(10,10)),true);f.events.clear();target.update(.15f);
            assertTrue(f.events.stream().noneMatch(e -> e.startsWith("enemy:")));source.stop();target.stop();
        }
    }

    @Test void pendingRemovedHeartAndOwnershipChangeUseMembershipRatherThanReinitialization() throws Exception {
        try (var f = new Fixture()) {
            var removed=f.heart(3,3,3);var transferred=f.heart(4,4,4);var source=f.owner();
            f.data.removeEntity(removed);f.data.setComponent(transferred,new Owner((short)3,(short)3));
            var target=f.owner();target.restoreState(roundTrip(source.captureState()));
            source.update(.15f);target.update(.15f);assertEquals(source.captureState(),target.captureState());
            // Normal behavior queues reveals on additions, not changes to existing query members.
            assertTrue(target.captureState().hearts().isEmpty());
            f.data.removeComponent(transferred,DungeonHeart.class);source.update(0);target.update(0);
            f.data.setComponent(transferred,new DungeonHeart());
            target.restoreState(roundTrip(source.captureState()));source.update(0);target.update(0);
            assertEquals(source.captureState(),target.captureState());assertEquals(1,target.captureState().hearts().size());
            source.stop();target.stop();
        }
    }

    @Test void bitmapsAreDetachedCanonicalAndPreservePendingDirtyDrain() {
        var source = new FogState((short)3, 10, 10);FogOfWarRules.explore(source, 9,9);FogOfWarRules.perceive(source, 1,1);
        var saved = source.captureState();source.clearAll();
        var copy = new FogState((short)3,10,10);copy.restoreState(saved);
        assertTrue(copy.isExplored(9,9));assertTrue(copy.isPerceived(1,1));
        assertEquals(Set.of(new Point(9,9), new Point(1,1)), copy.drainDirtyTiles());assertTrue(copy.drainDirtyTiles().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> saved.explored().set(0, 1L));
        assertThrows(IllegalArgumentException.class, () -> new FogState.Snapshot((short)3,1,1,true,List.of(2L),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FogState.Snapshot((short)3,1,1,true,List.of(0L),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FogState.Snapshot((short)3,Integer.MAX_VALUE,2,true,List.of(),List.of(),List.of()));
    }

    @Test void codecRejectsEveryTruncationTrailingCountsTagsAndMalformedValues() throws Exception {
        var map = new FogState.Snapshot((short)3, 20,20,false,List.of(1L,2L),List.of(8L),List.of(4L));
        var rich = new FogSnapshot(map,.123f,List.of(new FogSnapshot.HeartReveal(new Point(3,3),62)),
                Set.of(new Point(19,19)),Set.of(new EntityId(0),new EntityId(1024)),Set.of(new EntityId(6)),new EntityId(8));
        byte[] bytes = FogSnapshots.encode(rich);assertEquals(rich,FogSnapshots.decode(bytes));
        assertArrayEquals(bytes,FogSnapshots.encode(FogSnapshots.decode(bytes)));
        for (int n = 0; n < bytes.length; n++) {final int length=n;assertThrows(IOException.class, () -> FogSnapshots.decode(Arrays.copyOf(bytes,length)));}
        assertThrows(IOException.class, () -> FogSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        var empty = new FogSnapshot(new FogState.Snapshot((short)3,1,1,true,List.of(),List.of(),List.of()),0,List.of(),Set.of(),Set.of(),Set.of(),null);
        byte[] minimal = FogSnapshots.encode(empty);
        for (int offset : new int[]{0,4,10,14,19,23,27,35,39,43,47}) {
            byte[] bad = minimal.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class, () -> FogSnapshots.decode(bad));
        }
        for (int offset : new int[]{18,51}) {byte[] bad=minimal.clone();bad[offset]=2;assertThrows(IOException.class, () -> FogSnapshots.decode(bad));}
        byte[] bad = minimal.clone();ByteBuffer.wrap(bad).putFloat(31,Float.NaN);assertThrows(IOException.class, () -> FogSnapshots.decode(bad));
        assertThrows(IOException.class, () -> FogSnapshots.decode(new byte[FogSnapshots.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class, () -> new FogSnapshot.HeartReveal(new Point(0,0),63));
        assertThrows(IllegalArgumentException.class, () -> new FogSnapshot(empty.knowledge(),0,List.of(),Set.of(new Point(1,0)),Set.of(),Set.of(),null));
    }

    private static FogSnapshot roundTrip(FogSnapshot snapshot) throws IOException {return FogSnapshots.decode(FogSnapshots.encode(snapshot));}
    private static final class Fixture implements AutoCloseable {
        final DefaultEntityData data = new DefaultEntityData();final List<String> events = new ArrayList<>();final IKwdFile definition;
        final IMapDataInformation<IMapTileInformation> map;
        Fixture() {
            var terrain = new Terrain();terrain.setFlags(EnumSet.of(Terrain.TerrainFlag.TAGGABLE));
            var creature = new Creature();creature.getAttributes().setDistanceCanSee(3);creature.getAttributes().setPerceptionRange(2);
            var door = new Door();door.setFlags(EnumSet.noneOf(Door.DoorFlag.class));
            var radius = new Variable.MiscVariable();radius.setVariableId(Variable.MiscVariable.MiscType.DUNGEON_HEART_CLAIM_SCAN_RADIUS_TILES);radius.setValue(4);
            definition = (IKwdFile) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IKwdFile.class},(p,m,a) -> switch(m.getName()) {
                case "getTerrain" -> terrain;case "getCreature" -> creature;case "getDoorById" -> door;
                case "getVariables" -> Map.of(radius.getVariableId(),radius);case "getThings" -> List.of();
                default -> throw new AssertionError(m.getName());
            });
            var tile = (IMapTileInformation) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IMapTileInformation.class},(p,m,a) -> switch(m.getName()) {
                case "getTerrainId" -> (short)1;case "getOwnerId" -> (short)0;default -> throw new AssertionError(m.getName());
            });
            map = new IMapDataInformation<>() {
                public int getWidth(){return 20;}public int getHeight(){return 20;}
                public IMapTileInformation getTile(int x,int y){return x>=0&&y>=0&&x<20&&y<20?tile:null;}
                public void setTiles(List<IMapTileInformation> tiles){throw new AssertionError();}
            };
        }
        FogOfWarController owner() {
            var controller = new FogOfWarController(data,definition,map,(short)3,
                    points -> events.add("tiles:"+Arrays.stream(points).map(Point::toString).sorted().toList()),id -> events.add("enemy:"+id));
            controller.start();return controller;
        }
        EntityId heart(int owner,int x,int y) {
            var id=data.createEntity();data.setComponents(id,new RoomComponent((short)1,false,new Point(x,y)),new Owner((short)owner,(short)owner),new DungeonHeart());return id;
        }
        EntityId creature(int owner,int x,int y) {
            var id=data.createEntity();var c=new CreatureComponent();c.creatureId=1;
            data.setComponents(id,c,new Owner((short)owner,(short)owner),new Position(0,WorldUtils.pointToVector3f(x,y)));return id;
        }
        public void close(){data.close();}
    }
}
