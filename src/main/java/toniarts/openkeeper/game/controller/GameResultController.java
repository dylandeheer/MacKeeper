/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller;

import java.util.*;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.save.ResultSnapshots;

/** Authoritative terminal outcomes. Binding a saved outcome never repeats client delivery. */
public final class GameResultController {
    private final IGameTimer clock;
    private final PlayerService service;
    private final Set<Short> players;
    private final Map<Short, GameResult> results = new HashMap<>();

    public GameResultController(IGameTimer clock, PlayerService service, Collection<Short> players) {
        this.clock = Objects.requireNonNull(clock);
        this.service = Objects.requireNonNull(service);
        this.players = Set.copyOf(players);
    }

    public static GameResultController restoreState(ResultSnapshots.State state, IGameTimer clock,
            PlayerService service, Collection<Short> players) {
        var result = new GameResultController(clock, service, players);
        if (!Double.isFinite(clock.getGameTime()) || clock.getGameTime() < 0)
            throw new IllegalArgumentException("Invalid result clock");
        for (var entry : state.results().entrySet()) {
            var outcome = GameResult.restoreState(entry.getValue());
            if (!result.players.contains(entry.getKey()) || outcome.getElapsedSeconds() > clock.getGameTime())
                throw new IllegalArgumentException("Result does not belong to this saved world");
            result.results.put(entry.getKey(), outcome);
        }
        return result;
    }

    public synchronized void endGame(short playerId, boolean win) {
        if (results.containsKey(playerId)) return;
        if (!players.contains(playerId)) throw new IllegalArgumentException("Unknown result player");
        var outcome = GameResult.completed(win, clock.getGameTime());
        // Retain the outcome before delivering it, including if delivery re-enters or fails.
        results.put(playerId, outcome);
        service.endGame(win, playerId, outcome.getElapsedSeconds());
    }

    public synchronized GameResult getResult(short playerId) { return results.get(playerId); }

    public synchronized ResultSnapshots.State captureState() {
        var values = new TreeMap<Short, GameResult.State>();
        results.forEach((player, outcome) -> values.put(player, outcome.captureState()));
        return new ResultSnapshots.State(values);
    }
}
