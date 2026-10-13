/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.util.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.map.FlashTileControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.random.SimulationRandom;
import toniarts.openkeeper.game.state.loop.GameLoopManager;

/** Detached server simulation. File identity, loop phase and client session state are separate. */
public record SimulationState(GameEntityData.Snapshot entities, List<KeeperState> keepers,
        List<PlayerControlState> players, List<RoomState> rooms, List<FlashTileControl.State> flashes,
        LevelSnapshots.State level, GameLoopManager.SystemState systems,
        Map<TriggerRegistrySnapshots.Kind, TriggerRegistrySnapshots.State> triggers,
        SimulationRandom.State random, ResultSnapshots.State results) {
    public SimulationState {
        Objects.requireNonNull(entities);Objects.requireNonNull(level);Objects.requireNonNull(systems);
        Objects.requireNonNull(random);Objects.requireNonNull(results);
        keepers = List.copyOf(keepers);players = List.copyOf(players);rooms = List.copyOf(rooms);
        flashes = List.copyOf(flashes);triggers = Map.copyOf(triggers);
        var ids = new HashSet<Short>();
        for (var keeper : keepers) if (!ids.add(keeper.id())) throw new IllegalArgumentException("Duplicate saved keeper");
        var owners = new HashSet<Short>();
        for (var player : players) if (!owners.add(player.playerId())) throw new IllegalArgumentException("Duplicate saved player controller");
        if (!ids.equals(owners)) throw new IllegalArgumentException("Saved keeper/controller owners differ");
        if (!systems.taskAllocation().equals(systems.taskGraph().allocation())) throw new IllegalArgumentException("Task allocation differs");
    }

    public static SimulationState capture(GameLoopManager manager) {
        return manager.captureSnapshotWithSystems((game, systems) -> {
            var controller = (GameController) game;
            var map = (MapController) game.getGameWorldController().getMapController();
            try { return new SimulationState(((GameEntityData) manager.getEntityData()).capture(),
                    game.getLevelInfo().getPlayers().values().stream().sorted(Comparator.comparingInt(Keeper::getId)).map(Keeper::captureState).toList(),
                    game.getPlayerControllers().values().stream().sorted(Comparator.comparingInt(p -> p.getKeeper().getId()))
                            .map(p -> ((PlayerController)p).captureState()).toList(),
                    map.captureRoomStates(), map.captureFlashControls(),
                    new LevelSnapshots.State(LevelState.capture(game.getLevelInfo()), ((GameTimeController)game.getGameTimer()).captureState()),
                    systems, controller.captureTriggerRegistries(), game.getRandom().captureState(), controller.captureResults());
            } catch (java.io.IOException failure) {throw new java.io.UncheckedIOException(failure);}
        });
    }
}
