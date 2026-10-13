/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.save.TransitionSnapshots;
import toniarts.openkeeper.game.state.session.LocalGameSession;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import static org.junit.jupiter.api.Assertions.*;

class TransitionTriggerTest {
    @Test void restoredPendingCinematicKeepsRealTriggerBlockedUntilAcknowledgement() throws Exception {
        var source=new LocalGameSession();source.doTransition((short)1,new Vector3f(),(short)3);
        var condition=new TriggerGenericData();var type=TriggerGenericData.class.getDeclaredField("target");type.setAccessible(true);
        type.set(condition,TriggerGeneric.TargetType.GUI_TRANSITION_ENDS);
        var active=PlayerTriggerControl.class.getDeclaredMethod("isActive",TriggerGenericData.class);active.setAccessible(true);
        for(int i=0;i<5;i++) {
            var restored=new LocalGameSession();restored.restoreTransitions(TransitionSnapshots.decode(TransitionSnapshots.encode(source.getTransitions().capture())));
            var control=ObjectiveDisplayTest.control((short)3,(short)0,null,restored);
            assertEquals(false,active.invoke(control,condition));restored.transitionEnd();assertEquals(true,active.invoke(control,condition));
            assertTrue(source.isInTransition());restored.getEntityData().close();
        }
        source.getEntityData().close();
    }
}
