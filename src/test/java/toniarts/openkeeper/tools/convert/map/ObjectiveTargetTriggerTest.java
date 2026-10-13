/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.data.ObjectiveType;
import toniarts.openkeeper.game.trigger.AbstractThingTriggerControl;
import toniarts.openkeeper.game.trigger.creature.CreatureTriggerControl;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class ObjectiveTargetTriggerTest {
    @Test void playerActionAddsItsOwnerRegardlessOfCreatureOnlyArgument() {
        var players = new ArrayList<Short>();
        var game = proxy(IGameController.class, (name, args) -> {
            assertEquals("markPlayerObjective", name); players.add((short) args[0]); return null;
        });
        for (short argument : new short[]{0, 1, 2, 3, 255})
            new PlayerTriggerControl(game, level(argument), () -> 0, null, null, 1, (short) 5, null).update(.1f);
        assertEquals(List.of((short) 5, (short) 5, (short) 5, (short) 5, (short) 5), players);
    }

    @Test void creatureActionsUseBoundEntityAndOffCannotDispatchToKeeper() throws Exception {
        var objectives = new ArrayList<ObjectiveType>();
        var id = new EntityId(27);
        var game = proxy(IGameController.class, (name, args) -> {
            assertEquals("markCreatureObjective", name); assertEquals(id, args[0]);
            objectives.add((ObjectiveType) args[1]); return null;
        });
        var creature = proxy(ICreatureController.class, (name, args) -> {
            assertEquals("getEntityId", name); return id;
        });
        var bind = AbstractThingTriggerControl.class.getDeclaredMethod("setThing", Object.class); bind.setAccessible(true);
        for (short argument : new short[]{1, 2, 3, 0, 4, -1}) {
            var control = new CreatureTriggerControl(game, level(argument), () -> 0, null, null, 1, (short) 5, null);
            bind.invoke(control, creature); control.update(.1f);
        }
        assertEquals(Arrays.asList(ObjectiveType.KILL, ObjectiveType.IMPRISON, ObjectiveType.CONVERT, null), objectives);
        new CreatureTriggerControl(game, level((short) 1), () -> 0, null, null, 1, (short) 5, null).update(.1f);
        assertEquals(4, objectives.size());
    }

    private static ILevelInfo level(short argument) {
        var action = new TriggerAction(null); action.setId(1); action.setType(TriggerAction.ActionType.MAKE_OBJECTIVE);
        action.setUserData("targetId", argument);
        var kwd = proxy(IKwdFile.class, (name, args) -> {
            assertEquals("getTriggers", name); return Map.of(1, action);
        });
        return proxy(ILevelInfo.class, (name, args) -> switch (name) {
            case "getPaydayCycle" -> 0L;
            case "getLevelData" -> kwd;
            default -> throw new AssertionError(name);
        });
    }
}
