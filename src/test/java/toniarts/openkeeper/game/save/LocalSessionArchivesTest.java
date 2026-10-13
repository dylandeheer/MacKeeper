/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.jme3.renderer.Camera;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.fogofwar.FogState;
import toniarts.openkeeper.game.sound.SoundSnapshot;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.gui.nifty.WorkerAmountControl;
import toniarts.openkeeper.view.*;
import toniarts.openkeeper.view.camera.CameraState;
import toniarts.openkeeper.view.creature.CreatureCardsState;
import toniarts.openkeeper.view.fogofwar.FogSnapshot;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class LocalSessionArchivesTest {
    @TempDir Path directory;
    @Test void diskRetainsIndependentClientObservationsPendingActionsAndAllLifecycleVariants() throws Exception {
        for (boolean terminal : new boolean[]{false,true}) {
            var expected = fixture(terminal);var saved = expected;var bytes = LocalSessionArchives.encode(saved);
            for (int i=0;i<3;i++) {
                var path = directory.resolve("local.mkls");LocalSessionArchives.write(path,saved);saved=LocalSessionArchives.read(path);
                assertArrayEquals(bytes,LocalSessionArchives.encode(saved));assertEquals(expected.client(),saved.client());
                assertEquals(12,saved.server().simulation().keepers().getFirst().gold());
                assertEquals(7,saved.client().model().keepers().getFirst().gold());
                assertEquals(2,saved.client().model().pending().events().size());
                assertEquals(terminal,saved.client().active()==null);
            }
        }
    }
    @Test void envelopeRejectsEveryTruncationBitFlipAndAppendedByte() throws Exception {
        var bytes=LocalSessionArchives.encode(fixture(false));
        for(int i=0;i<bytes.length;i++) {
            var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->LocalSessionArchives.decode(truncated));
            var flipped=bytes.clone();flipped[i]^=1;assertThrows(IOException.class,()->LocalSessionArchives.decode(flipped));
        }
        assertThrows(IOException.class,()->LocalSessionArchives.decode(Arrays.copyOf(bytes,bytes.length+1)));
    }
    @Test void validChecksumsDoNotPermitMalformedNestedSectionsOrLifecycle() throws Exception {
        var archive=fixture(false);var bytes=LocalSessionArchives.encode(archive);
        for(int offset:new int[]{0,4,8,13}) {
            var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,Integer.MAX_VALUE);SimulationArchivesTest.resign(bad);
            assertThrows(IOException.class,()->LocalSessionArchives.decode(bad));
        }
        var booleanBad=bytes.clone();booleanBad[12]=2;SimulationArchivesTest.resign(booleanBad);
        assertThrows(IOException.class,()->LocalSessionArchives.decode(booleanBad));
        for(byte[] part:List.of(ClientModelSnapshots.encode(archive.client().model()),PlayerScreenSnapshots.encode(archive.client().screen()),
                FogSnapshots.encode(archive.client().fog()),SoundSnapshots.encode(archive.client().sound()),HandViewSnapshots.encode(archive.client().active().hand()))) {
            int offset=SimulationArchivesTest.find(bytes,part);assertTrue(offset>0);
            for(int field:new int[]{-4,4}) {
                var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset+field,Integer.MAX_VALUE);SimulationArchivesTest.resign(bad);
                assertThrows(IOException.class,()->LocalSessionArchives.decode(bad));
            }
        }
        int screen=SimulationArchivesTest.find(bytes,PlayerScreenSnapshots.encode(archive.client().screen()));
        var foreign=bytes.clone();ByteBuffer.wrap(foreign).putShort(screen+8,(short)4);SimulationArchivesTest.resign(foreign);
        assertThrows(IOException.class,()->LocalSessionArchives.decode(foreign));
    }
    @Test void invalidRosterCoordinatesAllocationAndOutcomeAreRejectedButTombstonesSurvive() {
        var archive=fixture(false);var client=archive.client();
        for(var event:List.of(new PresentationEvent.Gold((short)4,2,0),new PresentationEvent.Flower(new EntityId(2),5),
                new PresentationEvent.ChangedTiles(List.of(new Point(2,1))),new PresentationEvent.RevealedTiles(List.of(new Point(0,-1)),true,(short)3),
                new PresentationEvent.Ended(true,12.5))) {
            var model=new ClientModelState((short)3,client.model().keepers(),new PresentationQueue.State(List.of(event)));
            assertThrows(IllegalArgumentException.class,()->new LocalSessionArchive(false,archive.server(),copy(client,model,client.progress(),client.screen(),client.active())));
        }
        var historical=new PresentationEvent.Flower(new EntityId(1),3);
        var model=new ClientModelState((short)3,client.model().keepers(),new PresentationQueue.State(List.of(historical)));
        assertDoesNotThrow(()->new LocalSessionArchive(false,archive.server(),copy(client,model,client.progress(),client.screen(),client.active())));
        var extra=new ArrayList<>(client.model().keepers());extra.add(new Keeper(true,(short)4).captureState());
        var roster=new ClientModelState((short)3,extra,client.model().pending());
        assertThrows(IllegalArgumentException.class,()->new LocalSessionArchive(false,archive.server(),copy(client,roster,client.progress(),client.screen(),client.active())));
        var terminal=fixture(true);var result=GameResult.completed(false,12.5).captureState();
        var wrong=new PlayerProgressState((short)3,client.progress().attemptId(),null,result);
        assertThrows(IllegalArgumentException.class,()->new LocalSessionArchive(false,terminal.server(),copy(terminal.client(),terminal.client().model(),wrong,terminal.client().screen(),null)));
    }
    @Test void pendingTransitionNeedsACompletionSourceButHistoricalActionsNeedNotHaveAPendingFlag() {
        var archive=fixture(false);var s=archive.server().simulation();
        var pending=new SimulationState(s.entities(),s.keepers(),s.players(),s.rooms(),s.flashes(),s.level(),s.systems(),s.triggers(),s.random(),s.results(),s.loops(),new SessionTransitions.State(Set.of((short)3)));
        var server=new SimulationArchive(archive.server().definition(),pending);var c=archive.client();
        assertThrows(IllegalArgumentException.class,()->new LocalSessionArchive(false,server,c));
        var events=new PresentationQueue.State(List.of(new PresentationEvent.Transition((short)1,new PresentationEvent.Vec3(0,0,0))));
        var model=new ClientModelState((short)3,c.model().keepers(),events);
        var queued=copy(c,model,c.progress(),c.screen(),c.active());
        assertDoesNotThrow(()->new LocalSessionArchive(false,server,queued));
        assertDoesNotThrow(()->new LocalSessionArchive(false,archive.server(),queued));
    }
    @Test void ownerLifecycleCaptionAndBranchMustAgree() {
        var archive=fixture(false);var c=archive.client();
        assertThrows(IllegalArgumentException.class,()->copy(c,c.model(),c.progress(),c.screen(),null));
        var screen=new PlayerScreenState((short)3,true,true,true,PlayerScreenState.Route.CINEMATIC,"other",false);
        assertThrows(IllegalArgumentException.class,()->copy(c,c.model(),c.progress(),screen,c.active()));
        var progress=new PlayerProgressState((short)3,c.progress().attemptId(),new PlayerProgressState.Campaign(CampaignLevel.LevelType.Level,1,""),null);
        var branch=copy(c,c.model(),progress,c.screen(),c.active());
        assertThrows(IllegalArgumentException.class,()->new LocalSessionArchive(false,archive.server(),branch));
        assertThrows(IllegalArgumentException.class,()->new LocalSessionArchive(true,archive.server(),branch));
    }
    private static ClientState copy(ClientState c,ClientModelState model,PlayerProgressState progress,PlayerScreenState screen,ClientState.Active active) {
        return new ClientState(model,progress,screen,c.fog(),c.sound(),active);
    }
    private static LocalSessionArchive fixture(boolean terminal) {
        var base=SimulationArchivesTest.fixture();var s=base.simulation();
        var keeper=new Keeper(false,(short)3);keeper.setGold(12);var authoritative=keeper.captureState();keeper.setGold(7);
        var index=new PlayerControlState.ResearchIndex(List.of(),List.of());
        var player=new PlayerControlState((short)3,100,(short)1,List.of(),List.of(),List.of(),true,null,index,index,index,index,null,
                new PlayerStatsControl.State(Map.of(),Map.of(),Map.of()));
        var outcome=terminal?GameResult.completed(true,12.5).captureState():null;
        var world=new SimulationState(s.entities(),List.of(authoritative),List.of(player),s.rooms(),s.flashes(),s.level(),s.systems(),s.triggers(),s.random(),
                new ResultSnapshots.State(terminal?Map.of((short)3,outcome):Map.of()),s.loops(),s.transitions());
        var model=new ClientModelState((short)3,List.of(keeper.captureState()),new PresentationQueue.State(List.of(
                new PresentationEvent.Gold((short)3,12,0),new PresentationEvent.Flower(new EntityId(1),5))));
        var progress=new PlayerProgressState((short)3,UUID.fromString("d2c2b5b5-dd71-4a44-8a43-12acc52de3c8"),null,outcome);
        var screen=new PlayerScreenState((short)3,!terminal,true,true,terminal?PlayerScreenState.Route.CINEMATIC:PlayerScreenState.Route.HUD,"caption",terminal);
        var fog=new FogSnapshot(new FogState.Snapshot((short)3,2,3,true,List.of(3L),List.of(),List.of(1L)),.12f,List.of(),Set.of(),Set.of(new EntityId(1)),Set.of(),null);
        var sound=new SoundSnapshot((short)3,"",false,"DUNGEON",List.of("music.wav"),0,List.of(),null,null);
        ClientState.Active active=null;
        if(!terminal) {
            var camera=new Camera(640,480);camera.setFrustumPerspective(60,640f/480,1,1000);
            var state=new CameraState((short)3,false,CameraState.View.capture(camera),null,CameraState.Frame.capture(camera),true,true,List.of(),List.of());
            active=new ClientState.Active(state,new MessageState(false,List.of(),MessageState.Box.closed()),
                    new HudPanelState(.1f,"caption",null,List.of(1),false,true,0,HudPanelState.Menu.main(true)),
                    new InteractionSelection((short)3,false,PlayerInteractionState.InteractionState.Type.NONE,0),
                    new CreatureCardsState((short)3,(short)1,List.of(),List.of(),List.of(),Map.of(),WorkerAmountControl.State.TOTAL,0),
                    new HandViewState((short)3,8,false,false,List.of()),true);
        }
        return new LocalSessionArchive(false,new SimulationArchive(base.definition(),world),new ClientState(model,progress,screen,fog,sound,active));
    }
}
