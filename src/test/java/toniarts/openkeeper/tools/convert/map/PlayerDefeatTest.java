/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.export.*;
import com.jme3.export.binary.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class PlayerDefeatTest {
    @Test void wildcardAndSpecificKillerRunAuthoredActionsOnceAfterDefeat() {
        for (short filter : new short[]{0, 1}) for (int extra : new int[]{0, 1}) {
            Keeper keeper = new Keeper(false, (short)4);
            List<Short> ended = new ArrayList<>();
            PlayerTriggerControl control = control(keeper, filter, extra, ended);
            tick(control);
            assertTrue(ended.isEmpty());
            keeper.markDestroyed((short)1); // Hero player may destroy a keeper too.
            tick(control);
            assertEquals(List.of((short)4), ended);
            tick(control);
            assertEquals(List.of((short)4), ended);
        }
    }

    @Test void wrongKillerAndUnknownKillerDoNotSatisfyPredicate() {
        for (short killer : new short[]{0, 3}) {
            Keeper keeper = new Keeper(false, (short)4);
            keeper.markDestroyed(killer);
            List<Short> ended = new ArrayList<>();
            tick(control(keeper, (short)1, 0, ended));
            assertTrue(ended.isEmpty());
        }
        Keeper unknown = new Keeper(false, (short)4);
        unknown.markDestroyed((short)0);
        List<Short> ended = new ArrayList<>();
        tick(control(unknown, (short)0, 1, ended));
        assertTrue(ended.isEmpty());
    }

    @Test void firstDefeatRetainsItsAttribution() {
        Keeper keeper = new Keeper(false, (short)3);
        keeper.markDestroyed((short)4);
        keeper.markDestroyed((short)5);
        assertTrue(keeper.isDestroyed());
        assertEquals(4, keeper.getKilledByPlayerId());
    }

    @Test void binaryRoundTripPreservesDefeatAndAbsentHeart() throws Exception {
        Keeper keeper = new Keeper(false, (short)4);
        keeper.markDestroyed((short)1);
        Keeper restored = roundTrip(keeper);
        assertTrue(restored.isDestroyed());
        assertEquals(1, restored.getKilledByPlayerId());
        assertNull(restored.getDungeonHeartLocation());
        List<Short> ended = new ArrayList<>();
        tick(control(restored, (short)1, 0, ended));
        assertEquals(List.of((short)4), ended);
    }

    @Test void livingKeeperRoundTripPreservesHeartIncludingOrigin() throws Exception {
        for (Point point : List.of(new Point(0,0), new Point(23,51))) {
            Keeper keeper = new Keeper(false, (short)3);
            keeper.setDungeonHeartLocation(point);
            Keeper restored = roundTrip(keeper);
            assertFalse(restored.isDestroyed());
            assertEquals(0, restored.getKilledByPlayerId());
            assertEquals(point, restored.getDungeonHeartLocation());
        }
    }

    @Test void legacyDestroyedKeeperDoesNotInventKillerOrDiscardExistingHeartCoordinates() throws Exception {
        InputCapsule capsule = proxy(InputCapsule.class, (name,args) -> {
            if (args[0].equals("destroyed")) return true;
            if (args[0].equals("dungeonHeartLocationX")) return 7;
            if (args[0].equals("dungeonHeartLocationY")) return 9;
            return args[args.length-1];
        });
        Keeper restored = new Keeper();
        restored.read(proxy(JmeImporter.class, (name,args) -> capsule));
        assertTrue(restored.isDestroyed());
        assertEquals(0, restored.getKilledByPlayerId());
        assertEquals(new Point(7,9), restored.getDungeonHeartLocation());
    }

    private static Keeper roundTrip(Keeper keeper) throws Exception {
        var bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(keeper, bytes);
        return (Keeper) BinaryImporter.getInstance().load(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void tick(PlayerTriggerControl control) {
        for (int i=0; i<12; i++) control.update(.1f);
    }

    private static PlayerTriggerControl control(Keeper keeper, short filter, int extra, List<Short> ended) {
        TriggerGeneric condition = new TriggerGeneric(null);
        condition.setId(1); condition.setType(TriggerGeneric.TargetType.PLAYER_KILLED);
        condition.setUserData("playerId", filter); condition.setUserData("value", extra);
        condition.setIdChild(2); condition.setRepeatTimes((short)0);
        TriggerAction action = new TriggerAction(null);
        action.setId(2); action.setType(TriggerAction.ActionType.LOSE_GAME);
        IKwdFile kwd = proxy(IKwdFile.class, (name,args) -> switch(name) {
            case "getTriggers" -> Map.of(1, condition, 2, action);
            default -> throw new AssertionError(name);
        });
        ILevelInfo level = proxy(ILevelInfo.class, (name,args) -> switch(name) {
            case "getLevelData" -> kwd;
            case "getPaydayCycle" -> 0L;
            case "getPlayer" -> { assertEquals(keeper.getId(), args[0]); yield keeper; }
            default -> throw new AssertionError(name);
        });
        IGameController game = proxy(IGameController.class, (name,args) -> {
            assertEquals("endGame", name); assertEquals(false, args[1]);
            ended.add((short)args[0]); return null;
        });
        return new PlayerTriggerControl(game, level, () -> 0, null, null, 1, keeper.getId(), null);
    }
}
