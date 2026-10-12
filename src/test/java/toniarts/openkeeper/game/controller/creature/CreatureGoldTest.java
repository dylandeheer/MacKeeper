/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller.creature;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.Gold;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.controller.IObjectsController;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable.MiscType;

import static org.junit.jupiter.api.Assertions.*;

class CreatureGoldTest {
    private final DefaultEntityData data = new DefaultEntityData();
    private final EntityId imp = data.createEntity();
    private final List<Integer> piles = new ArrayList<>();
    private int failAfter = Integer.MAX_VALUE;

    @Test void fullPurseDropsIntoBoundedPilesWithoutLosingGold() throws Exception {
        CreatureController controller = creature(250, 100);

        controller.dropGold();
        controller.dropGold();

        assertEquals(List.of(100, 100, 50), piles);
        assertEquals(0, controller.getGold());
        assertEquals(250, controller.getMaxGold());
        assertEquals(250, piles.stream().mapToInt(Integer::intValue).sum());
    }

    @Test void emptyPurseCreatesNoObject() throws Exception {
        creature(0, 100).dropGold();
        assertTrue(piles.isEmpty());
    }

    @Test void failedPileCreationPreservesRemainingGoldAndCanRetry() throws Exception {
        CreatureController controller = creature(250, 100);
        failAfter = 1;

        assertThrows(IllegalStateException.class, controller::dropGold);
        assertEquals(150, controller.getGold());
        assertEquals(List.of(100), piles);

        failAfter = Integer.MAX_VALUE;
        controller.dropGold();
        assertEquals(List.of(100, 100, 50), piles);
        assertEquals(0, controller.getGold());
    }

    @Test void invalidPileLimitDoesNotLoseGoldOrLoopForever() throws Exception {
        CreatureController controller = creature(250, 0);
        assertThrows(IllegalStateException.class, controller::dropGold);
        assertEquals(250, controller.getGold());
        assertTrue(piles.isEmpty());
    }

    @Test void workingImpDropsGoldOnlyWhenNoTreasuryTaskIsAvailable() {
        List<String> actions = new ArrayList<>();
        for (boolean treasuryAvailable : List.of(false, true)) {
            actions.clear();
            ICreatureController impController = (ICreatureController) Proxy.newProxyInstance(
                    ICreatureController.class.getClassLoader(), new Class<?>[]{ICreatureController.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "shouldFleeOrAttack" -> false;
                        case "isAssignedTaskValid", "isAtAssignedTaskTarget", "isTooMuchGold" -> true;
                        case "dropGoldToTreasury" -> treasuryAvailable;
                        case "executeAssignedTask", "dropGold" -> { actions.add(method.getName()); yield null; }
                        default -> throw new AssertionError(method.getName());
                    });
            CreatureState.WORK.update(impController);
            assertEquals(treasuryAvailable ? List.of("executeAssignedTask")
                    : List.of("executeAssignedTask", "dropGold"), actions);
        }
    }

    private CreatureController creature(int gold, int pileLimit) throws Exception {
        data.setComponents(imp, new Gold(gold, 250), new Owner((short) 3, (short) 3),
                new Position(0, new Vector3f(7, 1, 9)));
        MiscVariable limit = new MiscVariable();
        var setter = MiscVariable.class.getDeclaredMethod("setValue", int.class);
        setter.setAccessible(true);
        setter.invoke(limit, pileLimit);
        IObjectsController objects = (IObjectsController) Proxy.newProxyInstance(
                IObjectsController.class.getClassLoader(), new Class<?>[]{IObjectsController.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("addLooseGold")) throw new AssertionError(method.getName());
                    assertEquals((short) 3, args[0]);
                    assertEquals(7, args[1]);
                    assertEquals(9, args[2]);
                    assertEquals(pileLimit, args[4]);
                    if (piles.size() == failAfter) throw new IllegalStateException("Synthetic spawn failure");
                    piles.add((int) args[3]);
                    EntityId pile = data.createEntity();
                    data.setComponent(pile, new Gold((int) args[3], pileLimit));
                    return pile;
                });
        return new CreatureController(imp, data, null, null, null, null,
                Map.of(MiscType.MAX_GOLD_PILE_OUTSIDE_TREASURY, limit), null, null, null, null, objects, null);
    }
}
