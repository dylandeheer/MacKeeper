/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state;

import de.lessvoid.nifty.builder.PanelBuilder;
import de.lessvoid.nifty.controls.Parameters;
import de.lessvoid.nifty.elements.Element;
import de.lessvoid.nifty.tools.SizeValue;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.gui.nifty.CreaturePortraitScrollControl;
import static org.junit.jupiter.api.Assertions.*;

class CreaturePortraitRestoreTest {
    @Test void quietScrollRestoreClampsToNewViewportAndFreshEmptyRosterResetsOldPosition() throws Exception {
        var f=new ObjectivePanelTest.Fixture();var screen=f.nifty.getScreen("hud");var root=screen.findElementById("layer");
        var viewport=new PanelBuilder("tab-creature-viewport") {{childLayoutHorizontal();width("160px");height("80px");}}.build(root);
        var visible=new PanelBuilder("tab-creature-visible") {{childLayoutHorizontal();width("160px");height("80px");}}.build(viewport);
        var content=new PanelBuilder("tab-creature-content") {{childLayoutHorizontal();width("400px");height("80px");}}.build(visible);
        var portraits=new ArrayList<Element>();
        for(int i=0;i<5;i++) portraits.add(new PanelBuilder("portrait-"+i) {{width("80px");height("80px");}}.build(content));
        screen.layoutLayers();
        var scroll=new CreaturePortraitScrollControl();scroll.bind(f.nifty,screen,viewport,new Parameters(new Properties()));scroll.setPortraits(portraits);
        scroll.restoreFirstVisible(4);assertEquals(3,scroll.getFirstVisible());
        assertEquals(-240,content.getConstraintX().getValueAsInt(1f));
        assertThrows(IllegalArgumentException.class,()->scroll.restoreFirstVisible(5));assertEquals(3,scroll.getFirstVisible());
        viewport.setConstraintWidth(SizeValue.px(400));screen.layoutLayers();scroll.restoreFirstVisible(4);assertEquals(0,scroll.getFirstVisible());
        viewport.setConstraintWidth(SizeValue.px(160));screen.layoutLayers();scroll.restoreFirstVisible(3);
        // Recreate the real manager with a reused, scrolled control and an empty new roster.
        f.nifty.loadControlFile("Interface/MyControls.xml");
        var workerElement=new de.lessvoid.nifty.builder.ControlBuilder("tab-workers","workerAmount") {{parameter("creatureId","0");}}.build(root);
        var workers=workerElement.getControl(toniarts.openkeeper.gui.nifty.WorkerAmountControl.class);workers.setTotal(42);
        var definition=ObjectivePanelTest.proxy(toniarts.openkeeper.tools.convert.map.IKwdFile.class,(method,args)-> {
            if(method.getName().equals("getImp")) return new toniarts.openkeeper.tools.convert.map.Creature();
            throw new AssertionError(method.getName());
        });
        var type=Class.forName("toniarts.openkeeper.game.state.PlayerScreenController$CreatureCardManager");
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var data=new com.simsilica.es.base.DefaultEntityData();
        try {
            var manager=constructor.newInstance(f.controller,definition,data,content,scroll,workers,screen,(short)3);
            try {
                var capture=type.getDeclaredMethod("capture");capture.setAccessible(true);
                var saved=(toniarts.openkeeper.view.creature.CreatureCardsState)capture.invoke(manager);
                assertEquals(0,saved.firstVisible());assertTrue(saved.cards().isEmpty());assertTrue(saved.creatures().isEmpty());
                assertEquals("0",workerElement.findNiftyControl("#workerTotal",de.lessvoid.nifty.controls.Label.class).getText());
                assertEquals(0,scroll.getFirstVisible());assertEquals(0,content.getConstraintX().getValueAsInt(1f));
            } finally {var cleanup=type.getDeclaredMethod("cleanup");cleanup.setAccessible(true);cleanup.invoke(manager);}
        } finally {data.close();}
    }
}
