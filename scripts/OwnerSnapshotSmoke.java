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
            LevelSnapshots.State level, Map<Short, PlayerStatsControl.State> statistics, List<RoomState> rooms, List<PlayerControlState> players,
            List<toniarts.openkeeper.game.controller.map.FlashTileControl.State> flashes,
            toniarts.openkeeper.game.logic.PlayerCreatureSystem.State creatureIndex,
            toniarts.openkeeper.game.logic.PlayerSpellbookSystem.State spellbookIndex,
            toniarts.openkeeper.game.logic.ChickenAiSystem.SavedState chickens, List<CreatureControlState> creatureControllers) { }

    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath().normalize() + "/";
        int ownerCount = 0, pickedUp = 0, observedBooks = 0, observedChickens = 0, observedCreatures = 0;
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
                var seededBooks = new java.util.concurrent.atomic.AtomicInteger();
                manager.runPlayerCommand(() -> {
                    var game = manager.getGameController();
                    var map = (MapController) game.getGameWorldController().getMapController();
                    for (var roomState : map.captureRoomStates()) {
                        var room = map.getRoomController(roomState.entityId());
                        var bookType = toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType.SPELL_BOOK;
                        if (!room.hasObjectControl(bookType)) continue;
                        var owner = game.getPlayerController(room.getOwnerId());
                        if (owner == null || owner.getResearchControl() == null) continue;
                        toniarts.openkeeper.game.controller.room.storage.IRoomObjectControl<EntityId> storage = room.getObjectControl(bookType);
                        if (storage.isFullCapacity() || storage.getAvailableCoordinates().isEmpty()) continue;
                        var spell = kwd.getKeeperSpells().getFirst();
                        owner.getSpellControl().setTypeAvailable(spell, true, false);
                        var point = storage.getAvailableCoordinates().iterator().next();
                        var book = game.getGameWorldController().getObjectsController().addRoomSpellBook(room.getOwnerId(), point.x, point.y, new PlayerSpell(spell.getId()));
                        require(storage.addItem(book, point) == null, "Probe book did not enter library");
                        seededBooks.incrementAndGet();break;
                    }
                    var controller = game.getPlayerController((short) 3);
                    if (controller == null) return;
                    var heart = controller.getKeeper().getDungeonHeartLocation();
                    require(heart != null, "Probe keeper has no heart location");
                    game.getGameWorldController().getObjectsController().spawnFreerangeChicken((short) 3,
                            toniarts.openkeeper.utils.WorldUtils.pointToVector3f(heart), game.getGameTimer().getGameTime());
                    ((MapController) game.getGameWorldController().getMapController()).flashTiles(List.of(new toniarts.openkeeper.utils.Point(0, 0)), (short) 3, 2);
                    controller.getRoomControl().setPortalsOpen(false);
                    controller.getKeeper().setMaxMana(controller.getKeeper().getMaxMana() + 123);
                    for (EntityId id : new TreeSet<>(data.findEntities(null, CreatureComponent.class, Owner.class))) {
                        if (data.getComponent(id, Owner.class).controlId != 3) continue;
                        game.getGameWorldController().pickUp(id, (short) 3);
                        if (!controller.getHandControl().isEmpty()) break;
                    }
                });
                long chickenDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (manager.captureSnapshotWithSystems((game, systems) -> systems.chickens().controllers().isEmpty())) {
                    require(System.nanoTime() < chickenDeadline, "Probe egg was not observed");Thread.sleep(20);
                }
                if (seededBooks.get() > 0) {
                    long bookDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (manager.captureSnapshotWithSystems((game, systems) -> systems.spellbooks().entries().isEmpty())) {
                        require(System.nanoTime() < bookDeadline, "Seeded research book was not observed");Thread.sleep(20);
                    }
                }
                for (int round = 0; round < 3; round++) {
                    var capture = manager.captureSnapshotWithSystems((game, systems) -> {
                        try {
                            List<GameTimer.State> timers = new ArrayList<>();
                            for (int i = 0; i < 16; i++) timers.add(game.getLevelInfo().getTimer(i).captureState());
                            Map<Short, PlayerStatsControl.State> statistics = new TreeMap<>();
                            game.getPlayerControllers().forEach((id, player) -> statistics.put(id, player.getStatsControl().captureState()));
                            return new Capture(data.capture(), game.getLevelInfo().getPlayers().values().stream().map(Keeper::captureState).toList(),
                                    game.getPlayerControllers().values().stream().map(IPlayerController::getHandControl).filter(Objects::nonNull).map(PlayerHandControl::captureState).toList(),
                                    ((GameTimeController) game.getGameTimer()).captureState(), List.copyOf(timers),
                                    new LevelSnapshots.State(LevelState.capture(game.getLevelInfo()), ((GameTimeController) game.getGameTimer()).captureState()), Map.copyOf(statistics),
                                    ((MapController) game.getGameWorldController().getMapController()).captureRoomStates(),
                                    game.getPlayerControllers().values().stream().map(player -> ((PlayerController) player).captureState()).toList(),
                                    ((MapController) game.getGameWorldController().getMapController()).captureFlashControls(), systems.creatures(), systems.spellbooks(), systems.chickens(), systems.creatureControllers());
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
                        var mapRef = new IMapController[1];
                        var world = (IGameWorldController) Proxy.newProxyInstance(IGameWorldController.class.getClassLoader(),
                                new Class<?>[]{IGameWorldController.class}, (p, m, a) -> {
                                    if (m.getName().equals("getMapController")) return mapRef[0];
                                    throw new AssertionError("Unexpected staged world call: " + m.getName());
                                });
                        var navigation = (toniarts.openkeeper.game.navigation.INavigationService) Proxy.newProxyInstance(
                                toniarts.openkeeper.game.navigation.INavigationService.class.getClassLoader(),
                                new Class<?>[]{toniarts.openkeeper.game.navigation.INavigationService.class}, (p, m, a) -> {throw new AssertionError("Restoring FSM replayed navigation: " + m.getName());});
                        var gameRef = (IGameController) Proxy.newProxyInstance(IGameController.class.getClassLoader(),
                                new Class<?>[]{IGameController.class}, (p, m, a) -> {
                                    if (m.getName().equals("getGameWorldController")) return world;
                                    if (m.getName().equals("getNavigationService")) return navigation;
                                    throw new AssertionError("Unexpected staged game call: " + m.getName());
                                });
                        var objects = ObjectsController.restoreBindings(kwd, staged, kwd.getVariables(), new GameTimeController(capture.clock()), gameRef, level);
                        byte[] roomBytes = RoomSnapshots.encode(capture.rooms());
                        var map = MapController.restore(kwd, objects, kwd.getVariables(), new GameTimeController(capture.clock()),
                                staged, level, RoomSnapshots.decode(roomBytes));
                        mapRef[0] = map;
                        int objectCount = 0;
                        for (var id : staged.findEntities(null, ObjectComponent.class)) {
                            var object = objects.createController(id);
                            require(id.equals(object.getEntityId()), "Object binding changed identity");objectCount++;
                        }
                        require(Arrays.equals(roomBytes, RoomSnapshots.encode(map.captureRoomStates())), "Live room state differs");
                        require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Binding live rooms mutated entities");
                        var flashes = MapControlSnapshots.decode(MapControlSnapshots.encode(capture.flashes()));
                        map.restoreFlashControls(flashes);require(flashes.equals(map.captureFlashControls()), "Flash deadlines differ");
                        List<IPlayerController> stagedPlayers = new ArrayList<>();
                        for (var playerState : capture.players()) {
                            var keeper = keepers.get(playerState.playerId());var beforeKeeper = keeper.captureState();
                            byte[] playerBytes = PlayerControlSnapshots.encode(playerState);
                            var player = PlayerController.restoreState(kwd, keeper, staged, PlayerControlSnapshots.decode(playerBytes), map::getRoomController);
                            require(Arrays.equals(playerBytes, PlayerControlSnapshots.encode(player.captureState())), "Player control state differs");
                            require(beforeKeeper.equals(keeper.captureState()), "Player construction changed keeper values/research");stagedPlayers.add(player);
                        }
                        var observed = CreatureIndexSnapshots.decode(CreatureIndexSnapshots.encode(capture.creatureIndex()));
                        var creatureSystem = toniarts.openkeeper.game.logic.PlayerCreatureSystem.restoreState(staged, kwd, stagedPlayers, observed);
                        try {require(observed.equals(creatureSystem.captureState()), "Observed creature cache differs");}
                        finally {creatureSystem.stop();}
                        var books = SpellbookIndexSnapshots.decode(SpellbookIndexSnapshots.encode(capture.spellbookIndex()));
                        observedBooks += books.entries().size();
                        var beforeResearch = stagedPlayers.stream().map(p -> ((PlayerController) p).captureState()).toList();
                        var bookSystem = toniarts.openkeeper.game.logic.PlayerSpellbookSystem.restoreState(staged, kwd, stagedPlayers, books);
                        try {
                            require(books.equals(bookSystem.captureState()), "Observed spellbook cache differs");
                            require(beforeResearch.equals(stagedPlayers.stream().map(p -> ((PlayerController) p).captureState()).toList()), "Book system binding replayed research discovery");
                        } finally {bookSystem.stop();}
                        var chickenState = ChickenSnapshots.decode(ChickenSnapshots.encode(capture.chickens()));
                        observedChickens += chickenState.controllers().size();
                        var chickenSystem = toniarts.openkeeper.game.logic.ChickenAiSystem.restoreState(staged, objects, chickenState);
                        try {require(chickenState.equals(chickenSystem.captureState()), "Chicken FSM or view observations differ");}
                        finally {chickenSystem.stop();}
                        // Task objects are a separate persistence layer. Identity-only stand-ins
                        // permit controller-value binding here; they must never execute behavior.
                        var taskNodes = new TreeMap<Long, toniarts.openkeeper.game.task.Task>();
                        for (var state : capture.creatureControllers()) if (state.failedTask() != null) {
                            var reference = state.failedTask();
                            taskNodes.computeIfAbsent(reference.nodeId(), key -> (toniarts.openkeeper.game.task.Task) Proxy.newProxyInstance(
                                    toniarts.openkeeper.game.task.Task.class.getClassLoader(), new Class<?>[]{toniarts.openkeeper.game.task.Task.class},
                                    (q, method, arguments) -> {
                                        if (method.getName().equals("getId")) return reference.taskId();
                                        throw new AssertionError("Task placeholder cannot execute: " + method.getName());
                                    }));
                        }
                        var bindings = new TaskNodeReferences.Bindings(taskNodes);
                        var captureNodes = new TaskNodeReferences.Capture();
                        taskNodes.values().forEach(captureNodes::reference);
                        var tasks = (toniarts.openkeeper.game.task.ITaskManager) Proxy.newProxyInstance(
                                toniarts.openkeeper.game.task.ITaskManager.class.getClassLoader(),
                                new Class<?>[]{toniarts.openkeeper.game.task.ITaskManager.class}, (p, m, a) -> {
                                    throw new AssertionError("Binding replayed task work: " + m.getName());
                                });
                        for (var controllerState : capture.creatureControllers()) {
                            byte[] encoded = CreatureControlSnapshots.encode(controllerState);
                            var restored = toniarts.openkeeper.game.controller.creature.CreatureController.restoreState(
                                    CreatureControlSnapshots.decode(encoded), bindings, staged, kwd.getCreature(controllerState.creatureType()),
                                    navigation, tasks, new GameTimeController(capture.clock()), kwd.getVariables(), null, null, map, level, objects, null);
                            require(controllerState.equals(restored.captureState(captureNodes)), "Creature FSM/retry state differs");observedCreatures++;
                        }
                        require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Binding players/flash controls mutated entities");
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
                        System.out.println("PASS " + name + " round=" + round + " keepers=" + keepers.size() + " hands=" + hands.size() + " rooms=" + capture.rooms().size() + " objects=" + objectCount + " books=" + books.entries().size() + " chickens=" + chickenState.controllers().size() + " creatureStates=" + capture.creatureControllers().size() + " controls=" + capture.players().size() + " flashes=" + capture.flashes().size() + " statOwners=" + capture.statistics().size() + " levelBytes=" + LevelSnapshots.encode(levelState).length
                                + " carried=" + capture.hands().stream().mapToInt(h -> h.bottomToTop().size()).sum() + " ticks=" + capture.clock().ticks());
                    } finally { staged.close(); }
                }
            } finally { manager.stop();data.close(); }
        }
        require(pickedUp > 0, "No actual pickup exercised");
        require(observedBooks > 0, "No real library research book observed");
        System.out.println("PASS observed library book round trips=" + observedBooks);
        require(observedChickens >= 12, "Missing observed chicken controllers");
        System.out.println("PASS observed chicken controller round trips=" + observedChickens);
        require(observedCreatures > 0, "No observed creature controllers");
        System.out.println("PASS creature controller value round trips=" + observedCreatures + " (task identities only, no task graph restore)");
        System.out.println("PASS 12 live owner captures; keeper round trips=" + ownerCount + " carried entity round trips=" + pickedUp);
        System.out.println("Keeper/hand/level/time/statistics/map/room/player/flash/creature-index/spellbook-index/object/chicken/creature-value bindings only. Full world restoration, resumed gameplay and complete save files remain unverified.");
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
