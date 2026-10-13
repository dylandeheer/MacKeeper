/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import com.jme3.app.state.AppStateManager;
import com.jme3.scene.Node;
import com.jme3.scene.control.AbstractControl;
import com.jme3.renderer.*;
import com.simsilica.es.base.DefaultEntityData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.Main;
import static org.junit.jupiter.api.Assertions.*;

class WorldViewOwnershipTest {
    @Test void menuAndGameResolveOwnNamedViewWhileStagedSceneClocksWaitForActivation() throws Exception {
        var ctor=Main.class.getDeclaredConstructor();ctor.setAccessible(true);var app=ctor.newInstance();
        var states=new AppStateManager(app);var data=new DefaultEntityData();var host=new Node();
        var menu=new NamedView("MainMenu: 3",data,host);var game=new NamedView("3",data,host);
        var clock=new Counter();game.getRoot().addControl(clock);game.setEnabled(false);
        states.attach(menu);states.attach(game);states.update(0);
        try {
            assertSame(menu,states.getState(PlayerEntityViewState.class)); // The inherited class is intentionally ambiguous.
            assertEquals(Map.of(),game.captureFlowers());game.restoreFlowers(Map.of());
            assertSame(host,menu.getRoot().getParent());assertNull(game.getRoot().getParent());
            host.updateLogicalState(1);assertEquals(0,clock.time);
            game.setEnabled(true);host.updateLogicalState(.25f);assertEquals(.25f,clock.time);
            menu.setEnabled(false);assertNull(menu.getRoot().getParent());assertSame(host,game.getRoot().getParent());
            game.setEnabled(false);host.updateLogicalState(1);assertEquals(.25f,clock.time);
            menu.setEnabled(true);assertSame(host,menu.getRoot().getParent());
            states.detach(game);assertThrows(IllegalStateException.class,game::captureFlowers);
            assertEquals(Map.of(),menu.captureFlowers());
        } finally {states.detach(menu);states.detach(game);states.update(0);data.close();}
    }
    static final class NamedView extends PlayerEntityViewState {
        NamedView(String id,DefaultEntityData data,Node root){super(null,null,data,(short)3,null,root);setId(id);}
    }
    static final class Counter extends AbstractControl {
        float time;
        @Override protected void controlUpdate(float tpf){time+=tpf;}
        @Override protected void controlRender(RenderManager manager,ViewPort port){}
    }
}
