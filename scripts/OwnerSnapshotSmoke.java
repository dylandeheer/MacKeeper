/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.simsilica.es.EntityId;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.player.PlayerHandControl;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.tools.convert.map.KwdFile;

/** Live keeper/hand/clock capture and staged owner binding, not a full restored session. */
class OwnerSnapshotSmoke {
    record Capture(GameEntityData.Snapshot entities, List<KeeperState> keepers,
            List<PlayerHandControl.State> hands, GameTimeController.State clock, List<GameTimer.State> timers,
            LevelSnapshots.State level, Map<Short, PlayerStatsControl.State> statistics, List<RoomState> rooms) { }

    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath().normalize() + "/";
        int ownerCount = 0, pickedUp = 0;
        for (String name : List.of("level1", "level17", "TrapTime", "level10")) {
            var kwd = KwdFile.load(base, Path.of(base, "Data/editor/maps/" + name + ".kwd"));
            var data = new GameEntityData();
            var service = (GameSessionServerService) Proxy.newProxyInstance(GameSessionServerService.class.getClassLoader(),
                    new Class<?>[]{GameSessionServerService.class}, (p, m, a) -> m.getName().equals("getEntityData") ? data : m.getReturnType() == boolean.class ? false : null);
            var manager = new GameLoopManager(kwd, service, null);
            try {
                manager.start();long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                for (;;) {
                    try { if (manager.captureSnapshot(game -> game.getGameTimer().getGameTime()) > 0) break; }
                    catch (IllegalStateException initializing) { }
                    require(System.nanoTime() < deadline, "World did not start: " + name);Thread.sleep(20);
                }
                // Exercise ordinary pickup of an actual owned creature. Use the
                // production command boundary, not mutation from a capture callback.
                manager.runPlayerCommand(() -> {
                    var game = manager.getGameController();
                    var controller = game.getPlayerController((short) 3);
                    if (controller == null) return;
                    for (EntityId id : new TreeSet<>(data.findEntities(null, CreatureComponent.class, Owner.class))) {
                        if (data.getComponent(id, Owner.class).controlId != 3) continue;
                        game.getGameWorldController().pickUp(id, (short) 3);
                        if (!controller.getHandControl().isEmpty()) break;
                    }
                });
                for (int round = 0; round < 3; round++) {
                    var capture = manager.captureSnapshot(game -> {
                        try {
                            List<GameTimer.State> timers = new ArrayList<>();
                            for (int i = 0; i < 16; i++) timers.add(game.getLevelInfo().getTimer(i).captureState());
                            Map<Short, PlayerStatsControl.State> statistics = new TreeMap<>();
                            game.getPlayerControllers().forEach((id, player) -> statistics.put(id, player.getStatsControl().captureState()));
                            return new Capture(data.capture(), game.getLevelInfo().getPlayers().values().stream().map(Keeper::captureState).toList(),
                                    game.getPlayerControllers().values().stream().map(IPlayerController::getHandControl).filter(Objects::nonNull).map(PlayerHandControl::captureState).toList(),
                                    ((GameTimeController) game.getGameTimer()).captureState(), List.copyOf(timers),
                                    new LevelSnapshots.State(LevelState.capture(game.getLevelInfo()), ((GameTimeController) game.getGameTimer()).captureState()), Map.copyOf(statistics),
                                    ((MapController) game.getGameWorldController().getMapController()).captureRoomStates());
                        } catch (IOException e) { throw new UncheckedIOException(e); }
                    });
                    var staged = GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(capture.entities())));
                    try {
                        Map<Short, Keeper> keepers = new HashMap<>();
                        for (var state : capture.keepers()) {
                            byte[] bytes = KeeperSnapshots.encode(state);
                            var restored = Keeper.restoreState(KeeperSnapshots.decode(bytes), kwd.getPlayers().get(state.id()));
                            require(state.equals(restored.captureState()), "Keeper values differ: " + name + " player=" + state.id());
                            keepers.put(state.id(), restored);ownerCount++;
                        }
                        var levelState = LevelSnapshots.decode(LevelSnapshots.encode(capture.level()));
                        var level = GameController.restoreLevelInfo(kwd, levelState.level(), keepers.values());
                        require(levelState.level().equals(LevelState.capture(level)), "Level values differ");
                        for (var state : capture.statistics().values()) {
                            var statistics = PlayerStatsControl.restoreState(PlayerStatsSnapshots.decode(PlayerStatsSnapshots.encode(state)), id -> kwd.getCreature((short) id));
                            require(state.equals(statistics.captureState()), "Interaction history differs");
                        }
                        byte[] beforeHands = EntitySnapshots.encode(staged.capture());
                        var objects = (IObjectsController) Proxy.newProxyInstance(IObjectsController.class.getClassLoader(),
                                new Class<?>[]{IObjectsController.class}, (p, m, a) -> {throw new AssertionError("Room binding created objects: " + m.getName());});
                        byte[] roomBytes = RoomSnapshots.encode(capture.rooms());
                        var map = MapController.restore(kwd, objects, kwd.getVariables(), new GameTimeController(capture.clock()),
                                staged, level, RoomSnapshots.decode(roomBytes));
                        require(Arrays.equals(roomBytes, RoomSnapshots.encode(map.captureRoomStates())), "Live room state differs");
                        require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Binding live rooms mutated entities");
                        List<PlayerHandControl> hands = new ArrayList<>();
                        for (var state : capture.hands()) {
                            var hand = PlayerHandControl.restoreState(keepers.get(state.playerId()), staged, HandSnapshots.decode(HandSnapshots.encode(state)));
                            require(state.equals(hand.captureState()), "Hand order differs");hands.add(hand);pickedUp += hand.getCount();
                        }
                        require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Binding hands mutated entities");
                        // Dropping from a staged hand must not affect the source
                        // stack or captured data, and must follow saved LIFO order.
                        for (var hand : hands) {
                            var order = hand.captureState().bottomToTop();
                            for (int i = order.size() - 1; i >= 0; i--) require(order.get(i).equals(hand.pop()), "Restored LIFO differs");
                        }
                        var clock = new GameTimeController(capture.clock());clock.start();
                        require(capture.clock().equals(clock.captureState()), "Startup reset restored clock");
                        clock.processTick(0.125f);
                        require(clock.captureState().ticks() == capture.clock().ticks() + 1, "Restored clock did not advance");
                        for (var timer : capture.timers()) {
                            var restored = GameTimer.restoreState(timer);require(timer.equals(restored.captureState()), "Timer reset");
                        }
                        Thread.sleep(300);
                        require(Arrays.equals(beforeHands, EntitySnapshots.encode(capture.entities())), "Staged hand/live play mutated capture");
                        System.out.println("PASS " + name + " round=" + round + " keepers=" + keepers.size() + " hands=" + hands.size() + " rooms=" + capture.rooms().size() + " statOwners=" + capture.statistics().size() + " levelBytes=" + LevelSnapshots.encode(levelState).length
                                + " carried=" + capture.hands().stream().mapToInt(h -> h.bottomToTop().size()).sum() + " ticks=" + capture.clock().ticks());
                    } finally { staged.close(); }
                }
            } finally { manager.stop();data.close(); }
        }
        require(pickedUp > 0, "No actual pickup exercised");
        System.out.println("PASS 12 live owner captures; keeper round trips=" + ownerCount + " carried entity round trips=" + pickedUp);
        System.out.println("Keeper/hand/level/time/statistics/map/room sections only. Full world restoration, resumed gameplay and complete save files remain unverified.");
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
