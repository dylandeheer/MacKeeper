/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import java.lang.reflect.*;
import com.jme3.network.HostedConnection;
import toniarts.openkeeper.game.network.game.GameHostedService;
import toniarts.openkeeper.game.network.game.GameClientService;
import toniarts.openkeeper.game.state.lobby.ClientInfo;
import toniarts.openkeeper.game.data.Keeper;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.data.ActionPoint;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import toniarts.openkeeper.utils.WorldUtils;
import static org.junit.jupiter.api.Assertions.*;

class ObjectiveDisplayTest {
    @Test void parsedActionReachesLocalPlayerAndDoesNotZoomForZeroActionPoint() {
        LocalGameSession session = new LocalGameSession();
        List<Integer> objectives = new ArrayList<>();
        session.addGameSessionListener(proxy(GameSessionListener.class, (name,args) -> {
            if (name.equals("onShowObjective")) objectives.add((int) args[0]);
            else if (!name.equals("equals")) fail("Unexpected callback: " + name);
            return false;
        }));
        control((short)3, (short)0, null, session).update(.1f);
        control((short)4, (short)0, null, session).update(.1f);
        assertEquals(List.of(8), objectives);
    }

    @Test void objectiveAndZoomAreAddressedToTheSamePlayer() {
        Thing.ActionPoint raw = new Thing.ActionPoint();
        raw.setId((short)2); raw.setStartX(4); raw.setEndX(6); raw.setStartY(2); raw.setEndY(4);
        ActionPoint point = new ActionPoint(raw);
        List<String> calls = new ArrayList<>();
        PlayerService service = proxy(PlayerService.class, (name,args) -> {
            assertEquals((short)4, args[1]);
            if (name.equals("showObjective")) assertEquals(8, args[0]);
            else if (name.equals("zoomViewToPoint")) assertEquals(WorldUtils.ActionPointToVector3f(point), (Vector3f) args[0]);
            else fail(name);
            calls.add(name); return null;
        });
        control((short)4, (short)2, point, service).update(.1f);
        assertEquals(List.of("showObjective", "zoomViewToPoint"), calls);
    }

    @Test void missingActionPointStillDisplaysInstruction() {
        List<String> calls = new ArrayList<>();
        PlayerService service = proxy(PlayerService.class, (name,args) -> { calls.add(name); return null; });
        control((short)3, (short)2, null, service).update(.1f);
        assertEquals(List.of("showObjective"), calls);
    }

    @Test void hostedSessionRoutesThroughClientCallbackOnlyToIntendedPlayer() throws Exception {
        GameHostedService host = new GameHostedService();
        List<Integer> first = new ArrayList<>(), second = new ArrayList<>();
        connect(host, (short)3, first);
        connect(host, (short)4, second);
        control((short)4, (short)0, null, host).update(.1f);
        host.showObjective(9, (short)7); // no connected player
        assertTrue(first.isEmpty());
        assertEquals(List.of(8), second);
        host.showObjective(9, (short)3);
        assertEquals(List.of(9), first);
        assertEquals(List.of(8), second);
    }

    // Substitute the RMI transport while retaining both real service callback implementations.
    static void connect(GameHostedService host, short keeper, List<Integer> received) throws Exception {
        GameClientService client = new GameClientService();
        client.addGameSessionListener(proxy(GameSessionListener.class, (name,args) -> {
            if (name.equals("onShowObjective")) received.add((int)args[0]);
            else if (!name.equals("equals")) fail("Unexpected callback: " + name);
            return false;
        }));
        Field clientCallback = GameClientService.class.getDeclaredField("sessionCallback");
        clientCallback.setAccessible(true);
        ClientInfo info = new ClientInfo(0, "local-test", keeper);
        info.setKeeper(new Keeper(false, keeper));
        Class<?> sessionType = Class.forName(GameHostedService.class.getName() + "$GameSessionImpl");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(GameHostedService.class, HostedConnection.class, ClientInfo.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(host, null, info);
        Field callback = sessionType.getDeclaredField("callback");
        callback.setAccessible(true); callback.set(session, clientCallback.get(client));
        Field players = GameHostedService.class.getDeclaredField("players");
        players.setAccessible(true);
        ((Map) players.get(host)).put(info, session);
    }

    static PlayerTriggerControl control(short player, short ap, ActionPoint point, PlayerService service) {
        TriggerAction action = new TriggerAction(null);
        action.setId(1); action.setType(TriggerAction.ActionType.DISPLAY_OBJECTIVE);
        action.setUserData("objectiveId", 8); action.setUserData("actionPointId", ap);
        IKwdFile kwd = proxy(IKwdFile.class, (name,args) -> switch(name) {
            case "getTriggers" -> Map.of(1, action);
            default -> throw new AssertionError(name);
        });
        ILevelInfo level = proxy(ILevelInfo.class, (name,args) -> switch(name) {
            case "getPaydayCycle" -> 0L;
            case "getLevelData" -> kwd;
            case "getActionPoint" -> { assertNotEquals((short)0, ((Number)args[0]).shortValue()); yield point; }
            default -> throw new AssertionError(name);
        });
        return new PlayerTriggerControl(null, level, () -> 0, null, null, 1, player, service);
    }
    interface Call { Object invoke(String name, Object[] args); }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m.getName(),a)));
    }
}
