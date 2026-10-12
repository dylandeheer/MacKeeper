/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller.state;

import com.badlogic.gdx.ai.fsm.State;
import com.badlogic.gdx.ai.msg.Telegram;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestoredStateMachineTest {
    enum Mode implements State<List<String>> {
        A,B,GLOBAL;
        public void enter(List<String> owner){owner.add("enter:"+name());}
        public void exit(List<String> owner){owner.add("exit:"+name());}
        public void update(List<String> owner){owner.add("update:"+name());}
        public boolean onMessage(List<String> owner,Telegram message){return false;}
    }
    @Test void bindsWithoutCallbacksThenRevertsAndUpdatesAsANormalStateMachine(){
        var events=new ArrayList<String>();var machine=new RestoredStateMachine<>(events,Mode.B,Mode.A,Mode.GLOBAL);
        assertTrue(events.isEmpty());assertEquals(Mode.B,machine.getCurrentState());assertEquals(Mode.A,machine.getPreviousState());
        machine.update();assertEquals(List.of("update:GLOBAL","update:B"),events);events.clear();
        assertTrue(machine.revertToPreviousState());assertEquals(List.of("exit:B","enter:A"),events);
        assertEquals(Mode.B,machine.getPreviousState());assertEquals(Mode.A,machine.getCurrentState());
    }
}
