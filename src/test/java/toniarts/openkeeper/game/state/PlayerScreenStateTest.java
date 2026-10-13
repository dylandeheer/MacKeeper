/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.*;
import de.lessvoid.nifty.builder.*;
import de.lessvoid.nifty.controls.Label;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.state.session.PlayerScreenState;
import toniarts.openkeeper.game.state.session.PlayerScreenState.Route;
import toniarts.openkeeper.game.save.PlayerScreenSnapshots;
import static org.junit.jupiter.api.Assertions.*;

class PlayerScreenStateTest {
    @Test void preservesActiveAndEndedFlagsCaptionsAndAllRoutesAcrossLoads() throws Exception {
        for(var route:Route.values())for(boolean paused:new boolean[]{false,true}) {
            var saved=new PlayerScreenState((short)3,true,paused,false,route,"Caption",false);
            var copy=saved;
            for(int i=0;i<3;i++)copy=PlayerScreenSnapshots.decode(PlayerScreenSnapshots.encode(copy));
            assertEquals(saved,copy);
        }
        var ended=new PlayerScreenState((short)3,false,true,true,Route.CINEMATIC,null,true);
        assertEquals(ended,PlayerScreenSnapshots.decode(PlayerScreenSnapshots.encode(ended)));
        assertThrows(IllegalArgumentException.class,()->new PlayerScreenState((short)3,false,false,true,Route.HUD,null,true));
        assertThrows(IllegalArgumentException.class,()->new PlayerScreenState((short)3,true,false,true,Route.HUD,null,true));
        assertThrows(IllegalArgumentException.class,()->new PlayerScreenState((short)3,false,false,true,Route.EMPTY,null,false));
        assertThrows(IllegalArgumentException.class,()->new PlayerScreenState((short)256,true,false,true,Route.HUD,null,false));
        assertThrows(IllegalArgumentException.class,()->new PlayerScreenState((short)3,true,false,true,Route.HUD,"x".repeat(65537),false));
        assertThrows(IllegalArgumentException.class,()->Route.fromScreenId("arbitrary"));
    }
    @Test void malformedSectionsFailRatherThanSelectingArbitraryUi() throws Exception {
        var data=PlayerScreenSnapshots.encode(new PlayerScreenState((short)3,true,false,true,Route.HUD,"Text",false));
        for(int i=0;i<data.length;i++) {
            var truncated=Arrays.copyOf(data,i);assertThrows(IOException.class,()->PlayerScreenSnapshots.decode(truncated));
        }
        assertThrows(IOException.class,()->PlayerScreenSnapshots.decode(Arrays.copyOf(data,data.length+1)));
        for(int offset:new int[]{0,4,10,11,12,data.length-1}) {
            var corrupt=data.clone();corrupt[offset]=9;assertThrows(IOException.class,()->PlayerScreenSnapshots.decode(corrupt));
        }
    }
    @Test void latestRouteSurvivesRequestsDuringNiftyTransitionsAndQuietRestorePreservesPanels() throws Exception {
        var f=new HudPanelStateTest.Fixture();var c=f.controller;
        new ScreenBuilder("empty"){{layer(new LayerBuilder("empty-layer"){{childLayoutVertical();}});}}.build(f.nifty);
        new PanelBuilder("menuButton"){{childLayoutVertical();}}.build(f.nifty.getScreen("hud").findElementById("layer"));
        c.goToScreen("hud");settle(f);
        // Another owner starts a transition; the outgoing screen still reports running until
        // the end-of-frame action. The immediate HUD request below is rejected by Nifty.
        f.nifty.gotoScreen("empty");c.goToScreen("cinematic");settle(f);
        assertEquals("cinematic",f.nifty.getCurrentScreen().getScreenId());
        c.goToScreen("hud");settle(f);
        c.goToScreen("cinematic");c.goToScreen("empty");c.goToScreen("hud");
        assertEquals(Route.HUD,c.getRequestedRoute());settle(f);
        assertEquals("hud",f.nifty.getCurrentScreen().getScreenId());
        c.pauseMenuNavigate("CONFIRMATION","QUIT","Exit","quitToOS()");c.setPause(true);
        var panels=c.captureHudState();
        var screen=new PlayerScreenState((short)3,true,true,false,Route.CINEMATIC,panels.cinematicText(),false);
        c.validateScreenRestore(screen);c.restoreScreenRouting(screen);settle(f);
        assertEquals("cinematic",f.nifty.getCurrentScreen().getScreenId());
        assertEquals(panels,c.captureHudState());
        for(int i=0;i<3;i++) {
            c.goToScreen("empty");c.restoreScreenRouting(PlayerScreenSnapshots.decode(PlayerScreenSnapshots.encode(screen)));settle(f);
            assertEquals("cinematic",f.nifty.getCurrentScreen().getScreenId());assertEquals(panels,c.captureHudState());
        }
        var bad=new PlayerScreenState((short)3,true,true,true,Route.POSSESSION,null,false);
        assertThrows(IllegalArgumentException.class,()->c.validateScreenRestore(bad));
        assertEquals(Route.CINEMATIC,c.getRequestedRoute());
        c.toggleGui();settle(f);assertEquals("empty",f.nifty.getCurrentScreen().getScreenId());
        c.toggleGui();settle(f);assertEquals("hud",f.nifty.getCurrentScreen().getScreenId());
        c.cleanup();assertThrows(IllegalStateException.class,()->c.validateScreenRestore(screen));
    }
    private static void settle(HudPanelStateTest.Fixture fixture) {
        for(int i=0;i<20;i++){fixture.nifty.update();fixture.controller.updateScreenRouting();}
        assertTrue(fixture.controller.isRouteReady());
    }
}
