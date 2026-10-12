/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state;

import com.jme3.app.state.AppStateManager;
import com.simsilica.es.base.DefaultEntityData;
import de.lessvoid.nifty.Nifty;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.GameResult;
import static org.junit.jupiter.api.Assertions.*;

class PlayerResultTest {
    @Test void clientRetainsFirstResultAndAttachesOnlyOneEndScreen() throws Exception {
        var fixture=new Fixture();
        fixture.player.endGame(false,91.25);
        var end=fixture.states.getState(EndGameState.class);
        assertNotNull(end);assertFalse(fixture.player.isEnabled());
        fixture.player.endGame(true,200);
        assertSame(end,fixture.states.getState(EndGameState.class));
        GameResult result=result(fixture.player);
        assertFalse(result.isWon());assertEquals(91.25,result.getElapsedSeconds());
    }

    @Test void invalidResultCannotPoisonTheClientSession() throws Exception {
        var fixture=new Fixture();
        assertThrows(IllegalArgumentException.class,()->fixture.player.endGame(true,Double.NaN));
        assertNull(result(fixture.player));assertNull(fixture.states.getState(EndGameState.class));
        fixture.player.endGame(true,0);
        assertTrue(result(fixture.player).isWon());
    }

    static GameResult result(PlayerState state)throws Exception {
        var f=PlayerState.class.getDeclaredField("gameResult");f.setAccessible(true);return (GameResult)f.get(state);
    }
    static class Fixture {
        final PlayerState player;final AppStateManager states;
        Fixture()throws Exception {
            Nifty fixtureNifty=new ObjectivePanelTest.Fixture().nifty;
            var constructor=Main.class.getDeclaredConstructor();constructor.setAccessible(true);
            Main app=constructor.newInstance();
            var display=new com.jme3.niftygui.NiftyJmeDisplay() {
                @Override public Nifty getNifty(){return fixtureNifty;}
            };
            var displayField=Main.class.getDeclaredField("niftyDisplay");displayField.setAccessible(true);displayField.set(app,display);
            var xmlField=Main.class.getDeclaredField("gameUiXml");xmlField.setAccessible(true);
            xmlField.set(app,("<nifty xmlns=\"http://nifty-gui.lessvoid.com/nifty-gui\"><screen id=\"empty\"><layer childLayout=\"vertical\"/></screen></nifty>").getBytes(StandardCharsets.UTF_8));
            states=new AppStateManager(app);
            player=new PlayerState(3,null,new DefaultEntityData(),false,app,null);
            player.initialize(states,app);
        }
    }
}
