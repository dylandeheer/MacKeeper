/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toniarts.openkeeper.game.controller.GameTimeController;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.random.SimulationRandom;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.task.TaskIdentityAllocator;
import toniarts.openkeeper.tools.convert.map.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationArchivesTest {
    @TempDir Path directory;

    @Test void repeatedDiskRoundTripsPreserveAllEncodedSectionsAndDefinition() throws Exception {
        var archive = fixture();var original = SimulationArchives.encode(archive);var path = directory.resolve("save.mksa");
        for (int i=0;i<5;i++) {
            SimulationArchives.write(path, archive);archive=SimulationArchives.read(path);
            assertEquals(fixture().definition(), archive.definition());assertArrayEquals(original, SimulationArchives.encode(archive));
        }
        try(var entries=Files.list(directory)){assertEquals(List.of(path),entries.toList());}
    }

    @Test void everyTruncationBitCorruptionAndExtraByteIsRejected() throws Exception {
        var bytes=SimulationArchives.encode(fixture());
        for(int i=0;i<bytes.length;i++) {
            var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->SimulationArchives.decode(truncated));
            var corrupt=bytes.clone();corrupt[i]^=1;assertThrows(IOException.class,()->SimulationArchives.decode(corrupt));
        }
        assertThrows(IOException.class,()->SimulationArchives.decode(Arrays.copyOf(bytes,bytes.length+1)));
    }

    @Test void checksummedMalformedLengthsPathsAndNestedSectionsAreRejected() throws Exception {
        var bytes=SimulationArchives.encode(fixture());
        for(int offset:new int[]{0,4,8,12}) {
            var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,Integer.MAX_VALUE);resign(bad);
            assertThrows(IOException.class,()->SimulationArchives.decode(bad));
        }
        var traversal=bytes.clone();traversal[16]='/';resign(traversal);
        assertThrows(IOException.class,()->SimulationArchives.decode(traversal));
        var entityMagic=ByteBuffer.allocate(4).putInt(EntitySnapshots.MAGIC).array();
        int section=find(bytes,entityMagic);assertTrue(section>0);
        var length=bytes.clone();ByteBuffer.wrap(length).putInt(section-4,SimulationArchives.MAX_PART_BYTES+1);resign(length);
        assertThrows(IOException.class,()->SimulationArchives.decode(length));
        var version=bytes.clone();ByteBuffer.wrap(version).putInt(section+4,99);resign(version);
        assertThrows(IOException.class,()->SimulationArchives.decode(version));
    }

    @Test void failedAndInterruptedWritesPreservePreviousFileAndRemoveTemporaryFile() throws Exception {
        var target=directory.resolve("save.mksa");var previous=SimulationArchives.encode(fixture());Files.write(target,previous);
        for(boolean interrupt:new boolean[]{false,true}) {
            try {
                assertThrows(IOException.class,()->SimulationArchives.writeAtomically(target,channel->{
                    channel.write(ByteBuffer.wrap(new byte[]{1,2,3}));
                    if(interrupt){Thread.currentThread().interrupt();channel.write(ByteBuffer.wrap(new byte[]{4}));}
                    throw new IOException("injected late write failure");
                }));
            } finally {Thread.interrupted();}
            assertArrayEquals(previous,Files.readAllBytes(target));
            try(var entries=Files.list(directory)){assertEquals(List.of(target),entries.toList());}
        }
    }

    @Test void differentDefinitionIsRejectedBeforeAnyWorldOrServiceIsCreated() throws Exception {
        var archive=fixture();var level=new GameLevel();var ticks=GameLevel.class.getDeclaredField("ticksPerSec");ticks.setAccessible(true);ticks.setInt(level,20);
        var source=archive.definition().source();var changed=new MapSource(source.mapPath(),List.of(new MapSource.File(source.mapPath(),3,"b".repeat(64))));
        var map=(IKwdFile)Proxy.newProxyInstance(IKwdFile.class.getClassLoader(),new Class[]{IKwdFile.class},(p,m,a)->switch(m.getName()){
            case "getMapSource"->changed;case "getGameLevel"->level;case "getVariables"->Map.of();default->throw new AssertionError(m.getName());
        });
        assertThrows(IOException.class,()->archive.restore(map,data->{fail("Service factory must not run for incompatible data");return null;}));
    }

    @Test void changedSettingsAndTickRateFailPreflightButMatchingDefinitionReachesStaging() throws Exception {
        var archive=fixture();var source=archive.definition().source();
        var level=new GameLevel();var ticks=GameLevel.class.getDeclaredField("ticksPerSec");ticks.setAccessible(true);ticks.setInt(level,20);
        var map=(IKwdFile)Proxy.newProxyInstance(IKwdFile.class.getClassLoader(),new Class[]{IKwdFile.class},(p,m,a)->switch(m.getName()){
            case "getMapSource"->source;case "getGameLevel"->level;case "getVariables"->Map.of();default->throw new AssertionError(m.getName());
        });
        var differentTicks=new SimulationArchive(new SimulationArchive.Definition(source,10,List.of()),archive.simulation());
        var differentSettings=new SimulationArchive(new SimulationArchive.Definition(source,20,List.of(
                new SimulationArchive.Setting(Variable.MiscVariable.MiscType.GAME_TICKS,7,0,0))),archive.simulation());
        for(var changed:List.of(differentTicks,differentSettings))
            assertThrows(IOException.class,()->changed.restore(map,data->{fail("Incompatible definition reached staging");return null;}));
        var expected=new IllegalArgumentException("staging reached");
        assertSame(expected,assertThrows(IllegalArgumentException.class,()->archive.restore(map,data->{throw expected;})));
    }

    static void resign(byte[] bytes)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");digest.update(bytes,0,bytes.length-32);
        System.arraycopy(digest.digest(),0,bytes,bytes.length-32,32);
    }
    static int find(byte[] bytes,byte[] pattern) {
        outer:for(int i=0;i<=bytes.length-pattern.length;i++){for(int j=0;j<pattern.length;j++)if(bytes[i+j]!=pattern[j])continue outer;return i;}return -1;
    }
    static SimulationArchive fixture() {
        var clock=new LifetimeSnapshots.Clock(12.5);var query=new TaskManagerSnapshots.Query(List.of(),List.of());
        var ids=new TaskIdentityAllocator.State(0);
        var systems=new GameLoopManager.SystemState(new PlayerCreatureSystem.State(List.of()),new PlayerSpellbookSystem.State(List.of()),
            new ChickenAiSystem.SavedState(List.of(),List.of(),List.of()),List.of(),new CreatureAiSystem.SavedState(List.of()),
            new PartyRegistryState(1,List.of(),Map.of(),Map.of()),ids,List.of(),new RoomTaskSnapshots.Capture(List.of(),List.of()),
            new EntityTaskSnapshots.Capture(List.of(),List.of()),new TaskGraphSnapshots.State(ids,List.of(),List.of(),List.of()),
            new TaskManagerSnapshots.State(List.of(),Map.of(),Map.of(),List.of(),List.of(),query,query,query,query),
            new ManaSnapshots.State(.25f,List.of(),Map.of(),Map.of(),Map.of(),List.of()),
            new ExperienceSnapshots.State(4,List.of(),List.of(),Map.of()),new KeeperEconomySnapshots.State(Map.of()),
            new CreatureSpawnSnapshots.State(12.5,new CreatureSpawnSnapshots.Settings(4,10,20,3,30),List.of(),List.of(),List.of()),
            new ChickenSpawnSnapshots.State(12.5,new ChickenSpawnSnapshots.Settings(8,20),List.of(),List.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of()),
            new LifetimeSnapshots.State(clock,clock,new LifetimeSnapshots.DeathState(clock,30,List.of())),
            new SlapSnapshots.State(12.5,4,List.of(),Map.of(),List.of(),List.of(),List.of(),List.of()),
            new HealthSnapshots.State(12.5,new HealthSnapshots.Settings(10,2,1),List.of(),List.of(),List.of(),List.of()),
            new AnimationSnapshots.State(clock,new AnimationSnapshots.Heart(12.5,5,true,List.of(),Map.of(),Map.of())),clock,
            new MovementSnapshots.State(List.of(),List.of(),List.of()),new PositionSnapshots.State(2,3,Map.of(),Map.of()));
        var triggers=new EnumMap<TriggerRegistrySnapshots.Kind,TriggerRegistrySnapshots.State>(TriggerRegistrySnapshots.Kind.class);
        for(var kind:TriggerRegistrySnapshots.Kind.values())triggers.put(kind,new TriggerRegistrySnapshots.State(kind,List.of(),List.of()));
        var level=new LevelSnapshots.State(new LevelState(Collections.nCopies(128,7),Collections.nCopies(16,new GameTimer.State(3.5f,true)),42,null,0),new GameTimeController.State(250,12.5));
        var simulation=new SimulationState(new GameEntityData.Snapshot(2,List.of()),List.of(),List.of(),List.of(),List.of(),level,systems,triggers,new SimulationRandom.State(731),new ResultSnapshots.State(Map.of()));
        String path="data/editor/maps/unit.kwd";
        return new SimulationArchive(new SimulationArchive.Definition(new MapSource(path,List.of(new MapSource.File(path,3,"a".repeat(64)))),20,List.of()),simulation);
    }
}
