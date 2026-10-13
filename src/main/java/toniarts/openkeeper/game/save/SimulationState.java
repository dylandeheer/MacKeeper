/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.util.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.map.FlashTileControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.random.SimulationRandom;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.utils.GameLoop;
import toniarts.openkeeper.utils.GameLoopClock;

/** Detached server simulation. Includes native loop phase and transition flags; file identity and rendered client state are separate. */
public record SimulationState(GameEntityData.Snapshot entities, List<KeeperState> keepers,
        List<PlayerControlState> players, List<RoomState> rooms, List<FlashTileControl.State> flashes,
        LevelSnapshots.State level, GameLoopManager.SystemState systems,
        Map<TriggerRegistrySnapshots.Kind, TriggerRegistrySnapshots.State> triggers,
        SimulationRandom.State random, ResultSnapshots.State results, List<GameLoopClock.State> loops, toniarts.openkeeper.game.state.session.SessionTransitions.State transitions) {
    public SimulationState {
        Objects.requireNonNull(entities);Objects.requireNonNull(level);Objects.requireNonNull(systems);
        Objects.requireNonNull(random);Objects.requireNonNull(results);Objects.requireNonNull(transitions);
        loops = List.copyOf(loops);
        if (loops.size() != 3) throw new IllegalArgumentException("Expected logic, animation and steering phases");
        keepers = List.copyOf(keepers);players = List.copyOf(players);rooms = List.copyOf(rooms);
        flashes = List.copyOf(flashes);triggers = Map.copyOf(triggers);
        var ids = new HashSet<Short>();
        for (var keeper : keepers) if (!ids.add(keeper.id())) throw new IllegalArgumentException("Duplicate saved keeper");
        var owners = new HashSet<Short>();
        for (var player : players) if (!owners.add(player.playerId())) throw new IllegalArgumentException("Duplicate saved player controller");
        if (!ids.containsAll(transitions.pendingPlayers())) throw new IllegalArgumentException("Saved transition references absent keeper");
        if (!ids.equals(owners)) throw new IllegalArgumentException("Saved keeper/controller owners differ");
        if (!systems.taskAllocation().equals(systems.taskGraph().allocation())) throw new IllegalArgumentException("Task allocation differs");
    }

    /** Validate before allocating a staged world, including for callers outside the file archive. */
    public void validateLoopRates(int ticksPerSecond) {
        if (ticksPerSecond <= 0 || ticksPerSecond > 1_000_000_000
                || loops.get(0).intervalNanos() != 1_000_000_000L / ticksPerSecond
                || loops.get(1).intervalNanos() != GameLoop.INTERVAL_FPS_60
                || loops.get(2).intervalNanos() != GameLoop.INTERVAL_FPS_60)
            throw new IllegalArgumentException("Saved loop rates differ from the game definition");
    }

    public static SimulationState capture(GameLoopManager manager) {
        return capture(manager, java.util.function.Function.identity());
    }

    /** Join render-thread client observations while the same exclusive server boundary is held. */
    public static <T> T capture(GameLoopManager manager, java.util.function.Function<SimulationState, T> joined) {
        Objects.requireNonNull(joined);
        return manager.captureSnapshotWithSystems((game, systems) -> {
            var controller = (GameController) game;
            var map = (MapController) game.getGameWorldController().getMapController();
            try { return joined.apply(new SimulationState(((GameEntityData) manager.getEntityData()).capture(),
                    game.getLevelInfo().getPlayers().values().stream().sorted(Comparator.comparingInt(Keeper::getId)).map(Keeper::captureState).toList(),
                    game.getPlayerControllers().values().stream().sorted(Comparator.comparingInt(p -> p.getKeeper().getId()))
                            .map(p -> ((PlayerController)p).captureState()).toList(),
                    map.captureRoomStates(), map.captureFlashControls(),
                    new LevelSnapshots.State(LevelState.capture(game.getLevelInfo()), ((GameTimeController)game.getGameTimer()).captureState()),
                    systems, controller.captureTriggerRegistries(), game.getRandom().captureState(), controller.captureResults(), manager.captureLoopPhases(), manager.captureTransitions()));
            } catch (java.io.IOException failure) {throw new java.io.UncheckedIOException(failure);}
        });
    }
}
