/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.control;

import com.jme3.scene.*;
import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.Health;
import toniarts.openkeeper.tools.convert.map.Door;
import toniarts.openkeeper.view.WorldViewState.*;
import static org.junit.jupiter.api.Assertions.*;

class UnitFlowerClockTest {
    @Test void repeatedClockBindingDoesNotReplayVisibilityCallbacksAndExpiresAtRemainingLifetime() throws Exception {
        var data=new DefaultEntityData();var id=data.createEntity();data.setComponent(id,new Health(100,100));
        var a=new Indicator(id,data);var b=new Indicator(id,data);a.setSpatial(new Node());b.setSpatial(new Node());
        try {
            a.show(4);a.update(1.25f);var saved=a.captureDisplay();int shown=b.shown;
            for(int i=0;i<3;i++) {b.restoreDisplay(saved);assertEquals(saved,b.captureDisplay());}
            assertEquals(shown,b.shown);assertEquals(0,b.hidden);
            a.update(2.5f);b.update(2.5f);assertTrue(b.isEnabled());assertEquals(a.captureDisplay(),b.captureDisplay());
            a.update(.25f);b.update(.25f);assertFalse(b.isEnabled());assertEquals(a.captureDisplay(),b.captureDisplay());
            assertEquals(1,b.hidden);
            b.restoreDisplay(saved);assertTrue(b.isEnabled());assertEquals(shown,b.shown);assertEquals(1,b.hidden);
        } finally {a.cleanup();b.cleanup();data.close();}
    }
    @Test void persistentVisibilityAndHiddenClocksSurviveBindingWithoutResetOrForeignDefinitionMutation() throws Exception {
        var data=new DefaultEntityData();var id=data.createEntity();data.setComponent(id,new Health(100,100));
        var indicator=new Indicator(id,data);indicator.setSpatial(new Node());
        try {
            indicator.persistent=true;indicator.update(5);var saved=indicator.captureDisplay();
            assertTrue(saved.visible());assertTrue(saved.elapsed()>saved.deadline());
            indicator.restoreDisplay(saved);indicator.update(.5f);assertTrue(indicator.isEnabled());
            indicator.persistent=false;indicator.update(.1f);assertFalse(indicator.isEnabled());
            saved=indicator.captureDisplay();int hidden=indicator.hidden;indicator.restoreDisplay(saved);
            assertEquals(saved,indicator.captureDisplay());assertEquals(hidden,indicator.hidden);
            var wrong=new Flower(Kind.DOOR,(short)2,true,0,10,null);var before=indicator.captureDisplay();
            assertThrows(IllegalArgumentException.class,()->indicator.restoreDisplay(wrong));assertEquals(before,indicator.captureDisplay());
        } finally {indicator.cleanup();data.close();}
    }
    /** Keep the production lifetime/control path while replacing only asset-dependent geometry. */
    static final class Indicator extends UnitFlowerControl<Door> {
        int shown,hidden;boolean persistent;
        Indicator(EntityId id,EntityData data)throws Exception {super(id,data,definition(),null);}
        static Door definition()throws Exception {var door=new Door();var field=Door.class.getDeclaredField("doorId");field.setAccessible(true);field.setShort(door,(short)1);return door;}
        @Override protected Spatial getFlower(){if(spatial==null)spatial=new Node("Indicator");return spatial;}
        @Override protected String getCenterIcon(){return null;}
        @Override protected float getHeight(){return 1;}
        @Override protected void onShow(){shown++;}
        @Override protected void onHide(){hidden++;}
        @Override protected boolean isPersistentVisibilityRequired(){return persistent;}
    }
}
