/*
 * Copyright (C) 2014-2018 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.trigger.player;

import com.jme3.util.SafeArrayList;
import java.util.Map;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.IGameController;
import toniarts.openkeeper.game.controller.IGameTimer;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.listener.DungeonBreachListener;
import toniarts.openkeeper.tools.convert.map.TriggerGeneric.TargetType;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.tools.convert.map.Player;

/**
 * Handles player triggers
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class PlayerTriggerLogicController implements IGameLogicUpdatable, toniarts.openkeeper.game.trigger.TriggerStateSource {

    private final IMapController mapController;
    private boolean listening;
    private final DungeonBreachListener breachListener = this::onDungeonBreached;

    private void onDungeonBreached(short defenderId, short attackerId) {
        for (PlayerTriggerControl control : playerTriggerControls.getArray()) {
            if (control.getPlayerId() == defenderId) control.fireBreachEvent(TargetType.PLAYER_DUNGEON_BREACHED);
        }
        // Defender actions may change conditions observed by the attacker.
        for (PlayerTriggerControl control : playerTriggerControls.getArray()) {
            if (control.getPlayerId() == attackerId) control.fireBreachEvent(TargetType.PLAYER_ENEMY_BREACHED);
        }
    }

    private final SafeArrayList<PlayerTriggerControl> playerTriggerControls = new SafeArrayList<>(PlayerTriggerControl.class);

    public PlayerTriggerLogicController(final IGameController gameController, final ILevelInfo levelInfo, final IGameTimer gameTimer, final IMapController mapController,
            final ICreaturesController creaturesController, final PlayerService playerService) {
        this.mapController = mapController;
        Map<Short, Player> players = levelInfo.getLevelData().getPlayers();
        for (Keeper keeper : levelInfo.getPlayers().values()) {
            int triggerId = players.get(keeper.getId()).getTriggerId();
            if (triggerId != 0) {
                playerTriggerControls.add(new PlayerTriggerControl(gameController, levelInfo, gameTimer, mapController, creaturesController, triggerId, keeper.getId(), playerService));
            }
        }

        // TODO: get notified on new parties (a.k.a. parties re-spawned)
    }

    private PlayerTriggerLogicController(IMapController map, java.util.List<PlayerTriggerControl> controls) {
        mapController = map;playerTriggerControls.addAll(controls);
    }
    public static PlayerTriggerLogicController restoreState(IGameController game, ILevelInfo level, IGameTimer timer, IMapController map,
            ICreaturesController creatures, PlayerService service,
            toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State saved) {
        if (saved.kind() != toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.PLAYER) throw new IllegalArgumentException("Wrong player registry");
        var expected = new java.util.HashSet<Short>();
        for (var keeper : level.getPlayers().values()) if (level.getLevelData().getPlayers().get(keeper.getId()).getTriggerId() != 0) expected.add(keeper.getId());
        if (!expected.equals(saved.entries().stream().map(e -> e.playerId()).collect(java.util.stream.Collectors.toSet()))) throw new IllegalArgumentException("Player trigger owners differ");
        var controls = new java.util.ArrayList<PlayerTriggerControl>();
        for (var entry : saved.entries()) {
            int trigger = level.getLevelData().getPlayers().get(entry.playerId()).getTriggerId();
            if (trigger != entry.tree().triggerId()) throw new IllegalArgumentException("Player trigger definition differs");
            var control = new PlayerTriggerControl(game,level,timer,map,creatures,trigger,entry.playerId(),service);
            control.restoreState(entry.tree());controls.add(control);
        }
        return new PlayerTriggerLogicController(map, controls);
    }
    @Override public toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State captureRegistry() {
        return new toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State(toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.PLAYER,
                playerTriggerControls.stream().map(c -> toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Entry.player(c.captureState(),c.getPlayerId())).toList(), java.util.List.of());
    }

    @Override
    public java.util.List<toniarts.openkeeper.game.save.TriggerSnapshots.State> captureTrees() {
        return playerTriggerControls.stream().map(control -> control.captureState()).toList();
    }

    @Override
    public void start() {
        if (!listening) { mapController.addBreachListener(breachListener);listening = true; }
    }

    @Override
    public void stop() {
        if (listening) { mapController.removeBreachListener(breachListener);listening = false; }
    }

    @Override
    public void processTick(float tpf) {
        for (PlayerTriggerControl playerTriggerControl : playerTriggerControls.getArray()) {
            playerTriggerControl.update(tpf);
        }
    }
}
