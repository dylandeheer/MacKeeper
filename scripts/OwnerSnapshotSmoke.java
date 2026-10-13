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
            toniarts.openkeeper.game.logic.ChickenAiSystem.SavedState chickens, List<CreatureControlState> creatureControllers, toniarts.openkeeper.game.logic.CreatureAiSystem.SavedState creatureAi, PartyRegistryState parties, toniarts.openkeeper.game.task.TaskIdentityAllocator.State taskAllocation, List<TileTaskSnapshots.State> tileTasks, RoomTaskSnapshots.Capture roomTasks, EntityTaskSnapshots.Capture entityTasks, TaskGraphSnapshots.State taskGraph, TaskManagerSnapshots.State taskManager, ManaSnapshots.State mana, ExperienceSnapshots.State experience, KeeperEconomySnapshots.State economy, CreatureSpawnSnapshots.State recruitment, ChickenSpawnSnapshots.State chickenSpawning, LifetimeSnapshots.State lifetimes, SlapSnapshots.State slaps, HealthSnapshots.State health, AnimationSnapshots.State animation, LifetimeSnapshots.Clock creatureView, MovementSnapshots.State movement, Map<String,List<TriggerSnapshots.State>> triggerTrees, Map<TriggerRegistrySnapshots.Kind,TriggerRegistrySnapshots.State> triggerRegistries, toniarts.openkeeper.game.random.SimulationRandom.State random) { }

    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath().normalize() + "/";
        int ownerCount = 0, pickedUp = 0, observedBooks = 0, observedChickens = 0, observedCreatures = 0, observedParties = 0, spawnedParties = 0, tileTaskCount = 0, roomTaskCount = 0, entityTaskCount = 0, objectiveTaskCount = 0, decoratorCount = 0, movingBodies = 0, triggerTrees = 0, triggerNodes = 0, triggerOwners = 0;
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
                    var creatureFactory = (CreaturesController) game.getGameWorldController().getCreaturesController();
                    var candidate = creatureFactory.getParties().stream().filter(p -> !p.getMembers().isEmpty())
                            .min(Comparator.comparingInt(p -> p.getPartyId()));
                    if (candidate.isPresent()) {
                        for (var room : map.captureRoomStates()) {
                            if (!map.getRoomController(room.entityId()).getRoom().isHeroGate() || room.destroyed()) continue;
                            var tile = room.coordinates().getFirst();
                            for (int spawn = 0; spawn < 2; spawn++) creatureFactory.spawnHeroParty(candidate.get().getPartyId(),
                                    toniarts.openkeeper.game.controller.creature.PartyType.INVASION_PARTY, new com.jme3.math.Vector2f(tile.x, tile.y));
                            break;
                        }
                    }
                    var controller = game.getPlayerController((short) 3);
                    if (controller == null) return;
                    var heart = controller.getKeeper().getDungeonHeartLocation();
                    require(heart != null, "Probe keeper has no heart location");
                    game.getGameWorldController().getObjectsController().spawnFreerangeChicken((short) 3,
                            toniarts.openkeeper.utils.WorldUtils.pointToVector3f(heart), game.getGameTimer().getGameTime());
                    ((MapController) game.getGameWorldController().getMapController()).flashTiles(List.of(new toniarts.openkeeper.utils.Point(0, 0)), (short) 3, 2);
                    // Seed a loose book through the ordinary object factory so its
                    // normal entity-query event creates a real fetch task.
                    var looseSpell = kwd.getKeeperSpells().getFirst();
                    game.getGameWorldController().getObjectsController().addRoomSpellBook((short)3, heart.x, heart.y, new PlayerSpell(looseSpell.getId()));
                    controller.getRoomControl().setPortalsOpen(false);
                    controller.getKeeper().setMaxMana(controller.getKeeper().getMaxMana() + 123);
                    for (EntityId id : new TreeSet<>(data.findEntities(null, CreatureComponent.class, Owner.class))) {
                        if (data.getComponent(id, Owner.class).controlId != 3) continue;
                        game.getGameWorldController().pickUp(id, (short) 3);
                        if (!controller.getHandControl().isEmpty()) break;
                    }
                    boolean slapped = false;
                    for (var id : new TreeSet<>(data.findEntities(null, CreatureComponent.class, Owner.class, Interaction.class, Position.class))) {
                        if (data.getComponent(id, Owner.class).ownerId != 3 || data.getComponent(id, InHand.class) != null) continue;
                        game.getGameWorldController().interact(id, (short)3);
                        if (data.getComponent(id, Slapped.class) != null) {slapped = true;break;}
                    }
                    require(slapped, "No ordinary owned creature slap exercised");
                });
                manager.runPlayerCommand(() -> {
                    // A controlled unconscious fixture drives the production rescue/capture query.
                    var game = manager.getGameController();
                    for (var id : new TreeSet<>(data.findEntities(null, CreatureComponent.class, Owner.class, Health.class, Position.class))) {
                        if (data.getComponent(id, Owner.class).ownerId != 3 || data.getComponent(id, InHand.class) != null || data.getComponent(id, Death.class) != null) continue;
                        data.setComponent(id, new Unconscious(game.getGameTimer().getGameTime()));break;
                    }
                });
                long slapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (manager.captureSnapshotWithSystems((game, systems) -> systems.slaps().creatures().isEmpty())) {
                    require(System.nanoTime() < slapDeadline, "Ordinary slap was not observed");Thread.sleep(20);
                }
                long taskDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (manager.captureSnapshotWithSystems((game, systems) -> systems.entityTasks().tasks().isEmpty())) {
                    require(System.nanoTime() < taskDeadline, "Seeded entity tasks were not observed");Thread.sleep(20);
                }
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
                                    ((MapController) game.getGameWorldController().getMapController()).captureFlashControls(), systems.creatures(), systems.spellbooks(), systems.chickens(), systems.creatureControllers(), systems.creatureAi(), systems.parties(), systems.taskAllocation(), systems.tileTasks(), systems.roomTasks(), systems.entityTasks(), systems.taskGraph(), systems.taskManager(), systems.mana(), systems.experience(), systems.economy(), systems.recruitment(), systems.chickenSpawning(), systems.lifetimes(), systems.slaps(), systems.health(), systems.animation(), systems.creatureView(), systems.movement(), ((GameController) game).captureTriggerTrees(), ((GameController) game).captureTriggerRegistries(), game.getRandom().captureState());
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
                        var objectRef = new IObjectsController[1];
                        var taskRef = new toniarts.openkeeper.game.task.ITaskManager[1];
                        var world = (IGameWorldController) Proxy.newProxyInstance(IGameWorldController.class.getClassLoader(),
                                new Class<?>[]{IGameWorldController.class}, (p, m, a) -> {
                                    if (m.getName().equals("getMapController")) return mapRef[0];
                                    if (m.getName().equals("getObjectsController")) return objectRef[0];
                                    if (m.getName().equals("getShotsController")) return null;
                                    if (m.getName().equals("addListener") || m.getName().equals("removeListener")) return null;
                                    throw new AssertionError("Unexpected staged world call: " + m.getName());
                                });
                        var navigation = (toniarts.openkeeper.game.navigation.INavigationService) Proxy.newProxyInstance(
                                toniarts.openkeeper.game.navigation.INavigationService.class.getClassLoader(),
                                new Class<?>[]{toniarts.openkeeper.game.navigation.INavigationService.class}, (p, m, a) -> {throw new AssertionError("Restoring FSM replayed navigation: " + m.getName());});
                        var gameRef = (IGameController) Proxy.newProxyInstance(IGameController.class.getClassLoader(),
                                new Class<?>[]{IGameController.class}, (p, m, a) -> {
                                    if (m.getName().equals("getGameWorldController")) return world;
                                    if (m.getName().equals("getNavigationService")) return navigation;
                                    if (m.getName().equals("getTaskManager")) return taskRef[0];
                                    if (m.getName().equals("getEntityLookupService")) return null;
                                    throw new AssertionError("Unexpected staged game call: " + m.getName());
                                });
                        var savedRandom = RandomSnapshots.decode(RandomSnapshots.encode(capture.random()));
                        var random = toniarts.openkeeper.game.random.SimulationRandom.restore(savedRandom);
                        var objects = ObjectsController.restoreBindings(kwd, staged, kwd.getVariables(), new GameTimeController(capture.clock()), gameRef, level, random);
                        objectRef[0] = objects;
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
                        var economyState = KeeperEconomySnapshots.decode(KeeperEconomySnapshots.encode(capture.economy()));
                        var economySystem = toniarts.openkeeper.game.logic.keeper.KeeperEconomySystem.restoreState(world, kwd, stagedPlayers, staged, economyState);
                        try {require(economyState.equals(economySystem.captureState()), "Keeper decision state differs");}
                        finally {economySystem.stop();}
                        var slapState = SlapSnapshots.decode(SlapSnapshots.encode(capture.slaps()));
                        var beforeSlapStats = stagedPlayers.stream().map(p -> p.getStatsControl().captureState()).toList();
                        var slapSystem = toniarts.openkeeper.game.logic.SlapSystem.restoreState(staged, kwd, stagedPlayers, slapState);
                        try {
                            require(slapState.equals(slapSystem.captureState()), "Slap clocks/observations differ");
                            require(beforeSlapStats.equals(stagedPlayers.stream().map(p -> p.getStatsControl().captureState()).toList()), "Slap binding replayed statistics");
                            require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Slap binding replayed effects");
                        } finally {slapSystem.stop();}
                        var manaState = ManaSnapshots.decode(ManaSnapshots.encode(capture.mana()));
                        var beforeMana = stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList();
                        var manaSystem = toniarts.openkeeper.game.logic.ManaCalculatorLogic.restoreState(stagedPlayers, staged, manaState);
                        try {
                            require(manaState.equals(manaSystem.captureState()), "Mana timer/cache observations differ");
                            require(beforeMana.equals(stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList()), "Mana binding credited resources");
                        } finally {manaSystem.stop();}
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
                        // Build the actual executable task objects first, then close creature/target cycles.
                        var creatures = CreaturesController.restoreBindings(kwd, staged, kwd.getVariables(), new GameTimeController(capture.clock()), gameRef, map, level, random);
                        var experienceState = ExperienceSnapshots.decode(ExperienceSnapshots.encode(capture.experience()));
                        var experienceSystem = toniarts.openkeeper.game.logic.CreatureExperienceSystem.restoreState(staged, kwd, creatures, experienceState);
                        try {require(experienceState.equals(experienceSystem.captureState()), "Experience timers/query memberships differ");}
                        finally {experienceSystem.stop();}
                        var graphState = TaskGraphSnapshots.decode(TaskGraphSnapshots.encode(capture.taskGraph()));
                        require(graphState.equals(capture.taskGraph()), "Task graph codec differs");
                        var taskAllocator = new toniarts.openkeeper.game.task.TaskIdentityAllocator(graphState.allocation(), () -> {
                            throw new AssertionError("Restoring tasks consumed the wall clock");
                        });
                        var removedRooms = new HashMap<EntityId, toniarts.openkeeper.game.controller.room.IRoomController>();
                        for (var state : graphState.removedRooms()) {
                            require(map.getRoomController(state.entityId()) == null, "Removed room is still in map index");
                            removedRooms.put(state.entityId(), RoomControllerFactory.restoreRemovedRoom(staged, kwd, state, objects, kwd.getVariables(), new GameTimeController(capture.clock())));
                        }
                        var recruitmentState = CreatureSpawnSnapshots.decode(CreatureSpawnSnapshots.encode(capture.recruitment()));
                        for (var state : recruitmentState.removedRooms()) {
                            require(map.getRoomController(state.entityId()) == null, "Removed entrance remains in map");
                            var existing = removedRooms.get(state.entityId());
                            if (existing != null) require(state.equals(((toniarts.openkeeper.game.controller.room.AbstractRoomController) existing).captureState()), "Conflicting removed room dependencies");
                            else removedRooms.put(state.entityId(), RoomControllerFactory.restoreRemovedRoom(staged, kwd, state, objects, kwd.getVariables(), new GameTimeController(capture.clock())));
                        }
                        var recruitmentSystem = toniarts.openkeeper.game.logic.CreatureSpawnSystem.restoreState(creatures, stagedPlayers,
                                kwd, map, staged, id -> removedRooms.containsKey(id) ? removedRooms.get(id) : map.getRoomController(id), recruitmentState);
                        try {
                            require(recruitmentState.equals(recruitmentSystem.captureState()), "Recruitment clock/entrances differ");
                            recruitmentSystem.start();recruitmentSystem.start();
                            require(recruitmentState.equals(recruitmentSystem.captureState()), "Recruitment activation changed state");
                        } finally {recruitmentSystem.stop();}
                        var spawningState = ChickenSpawnSnapshots.decode(ChickenSpawnSnapshots.encode(capture.chickenSpawning()));
                        for (var state : spawningState.removedRooms()) {
                            require(map.getRoomController(state.entityId()) == null, "Removed hatchery remains in map");
                            var existing = removedRooms.get(state.entityId());
                            if (existing != null) require(state.equals(((toniarts.openkeeper.game.controller.room.AbstractRoomController) existing).captureState()), "Conflicting hatchery room dependencies");
                            else removedRooms.put(state.entityId(), RoomControllerFactory.restoreRemovedRoom(staged, kwd, state, objects, kwd.getVariables(), new GameTimeController(capture.clock())));
                        }
                        var spawningSystem = toniarts.openkeeper.game.logic.ChickenSpawnSystem.restoreState(staged, objects, stagedPlayers,
                                map, id -> removedRooms.containsKey(id) ? removedRooms.get(id) : map.getRoomController(id), spawningState);
                        try {
                            require(spawningState.equals(spawningSystem.captureState()), "Chicken spawn clock/coop/population observations differ");
                            spawningSystem.start();spawningSystem.start();
                            require(spawningState.equals(spawningSystem.captureState()), "Chicken spawn activation changed state");
                        } finally {spawningSystem.stop();}
                        var lifetimeState = LifetimeSnapshots.decode(LifetimeSnapshots.encode(capture.lifetimes()));
                        var noDeathCleanup = (toniarts.openkeeper.game.logic.IEntityPositionLookup) Proxy.newProxyInstance(
                                toniarts.openkeeper.game.logic.IEntityPositionLookup.class.getClassLoader(),
                                new Class<?>[]{toniarts.openkeeper.game.logic.IEntityPositionLookup.class},
                                (p, m, a) -> {throw new AssertionError("Lifetime binding replayed cleanup: " + m.getName());});
                        // Rebuild base execution trees against the same authored definitions.
                        // Concrete registry owner identities/listeners are a separate restore step.
                        for (var treeList : capture.triggerTrees().values()) for (var savedTree : treeList) {
                            var decodedTree = TriggerSnapshots.decode(TriggerSnapshots.encode(savedTree));
                            var tree = new toniarts.openkeeper.game.trigger.TriggerControl(null, level,
                                    new GameTimeController(capture.clock()), map, creatures, decodedTree.triggerId());
                            tree.restoreState(decodedTree);
                            require(decodedTree.equals(tree.captureState()), "Trigger execution tree differs");
                            require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Trigger tree binding changed entities");
                            triggerTrees++;triggerNodes += decodedTree.nodes().size();
                        }
                        var movementState = MovementSnapshots.decode(MovementSnapshots.encode(capture.movement()));
                        var movementSystem = toniarts.openkeeper.game.logic.MovementSystem.restoreState(staged, movementState);
                        try {
                            require(movementState.equals(movementSystem.captureState()), "Movement dynamics/geometry/query observations differ");
                            require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Movement binding changed entities");
                            movingBodies += movementState.bodies().size();
                        } finally {movementSystem.stop();}
                        var viewClock = CreatureViewSnapshots.decode(CreatureViewSnapshots.encode(capture.creatureView()));
                        var derivedSystems = new ArrayList<toniarts.openkeeper.game.logic.IGameLogicUpdatable>();
                        var beforeDerivedKeepers = stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList();
                        try {
                            var viewSystem = toniarts.openkeeper.game.logic.CreatureViewSystem.restoreState(staged, viewClock);
                            derivedSystems.add(viewSystem);
                            derivedSystems.add(toniarts.openkeeper.game.logic.WoodenBridgeDecaySystem.restoreBindings(staged, new GameTimeController(capture.clock()), world));
                            derivedSystems.add(new toniarts.openkeeper.game.logic.CreatureMoodSystem(staged, kwd, stagedPlayers));
                            derivedSystems.add(new toniarts.openkeeper.game.logic.DoorViewSystem(staged, noDeathCleanup));
                            var playerMap = new HashMap<Short,IPlayerController>();stagedPlayers.forEach(p -> playerMap.put(p.getKeeper().getId(), p));
                            derivedSystems.add(new toniarts.openkeeper.game.logic.LooseObjectSystem(staged, map, playerMap, noDeathCleanup, world));
                            derivedSystems.add(new toniarts.openkeeper.game.logic.HaulingSystem(staged));
                            require(viewClock.equals(viewSystem.captureTime()), "Creature view clock differs");
                            require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Derived-system binding changed entities");
                            require(beforeDerivedKeepers.equals(stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList()), "Derived-system binding changed keeper values");
                        } finally {for (var system : derivedSystems.reversed()) system.stop();}
                        var animationState = AnimationSnapshots.decode(AnimationSnapshots.encode(capture.animation()));
                        var heartConstruction = toniarts.openkeeper.game.logic.DungeonHeartConstruction.restoreState(staged, animationState.heart());
                        try {
                            var fallSystem = toniarts.openkeeper.game.logic.CreatureFallSystem.restoreState(staged, animationState.fall());
                            try {
                                require(animationState.equals(new AnimationSnapshots.State(fallSystem.captureTime(), heartConstruction.captureState())), "Animation clock/construction groups differ");
                                require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Animation binding changed entities");
                            } finally {fallSystem.stop();}
                        } finally {heartConstruction.stop();}
                        var healthState = HealthSnapshots.decode(HealthSnapshots.encode(capture.health()));
                        var beforeHealthKeepers = stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList();
                        var healthSystem = toniarts.openkeeper.game.logic.HealthSystem.restoreState(staged, noDeathCleanup, creatures,
                                level, stagedPlayers, map, world, healthState);
                        try {
                            require(healthState.equals(healthSystem.captureState()), "Health clock/settings/observations differ");
                            require(beforeHealthKeepers.equals(stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList()), "Health binding changed keeper counters");
                            require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Health binding changed entities");
                        } finally {healthSystem.stop();}
                        var decay = toniarts.openkeeper.game.logic.DecaySystem.restoreState(staged, lifetimeState.decay());
                        try {
                            var torture = toniarts.openkeeper.game.logic.CreatureTorturingSystem.restoreState(staged, creatures, map, lifetimeState.torture());
                            try {
                                var death = toniarts.openkeeper.game.logic.DeathSystem.restoreState(staged, noDeathCleanup, lifetimeState.death());
                                try {
                                    require(lifetimeState.equals(new LifetimeSnapshots.State(decay.captureTime(), torture.captureTime(), death.captureState())),
                                            "Lifetime clocks/death observations differ");
                                    require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Lifetime binding mutated entities");
                                } finally {death.stop();}
                            } finally {torture.stop();}
                        } finally {decay.stop();}
                        var tasks = toniarts.openkeeper.game.task.TaskManager.stageRestore(staged, world, map, objects, creatures,
                                navigation, stagedPlayers, level, null, kwd.getVariables(), taskAllocator);
                        taskRef[0] = tasks;
                        try {
                            var graph = TaskGraphSnapshots.restoreShells(graphState, new TaskGraphSnapshots.Context(taskAllocator,
                                    staged, kwd, navigation, map, id -> removedRooms.containsKey(id) ? removedRooms.get(id) : map.getRoomController(id),
                                    tasks, world, null, objects, id -> stagedPlayers.stream().filter(player -> player.getKeeper().getId() == id).findFirst().orElse(null), level));
                            var decodedControllers = new ArrayList<CreatureControlState>();
                            for (var state : capture.creatureControllers()) decodedControllers.add(CreatureControlSnapshots.decode(CreatureControlSnapshots.encode(state)));
                            // Keep every weak-cache entry pinned until all task and party owners are reconstructed.
                            var controllers = creatures.restoreCreatureControllers(decodedControllers, graph.bindings());
                            graph.bindCreatures(creatures::getBoundController);
                            var managerState = TaskManagerSnapshots.decode(TaskManagerSnapshots.encode(capture.taskManager()));
                            tasks.bindRestoredState(managerState, graph, creatures::getBoundController,
                                    id -> removedRooms.containsKey(id) ? removedRooms.get(id) : map.getRoomController(id));
                            tasks.start();
                            var captureNodes = new TaskNodeReferences.Capture();
                            require(capture.creatureControllers().equals(creatures.captureControllerStates(captureNodes)), "Creature cache/FSM/retry state differs");
                            require(managerState.equals(tasks.captureState(captureNodes)), "Task manager roots/queues/reservations/queries/continuations differ");
                            require(graphState.equals(tasks.captureTaskGraph(captureNodes)), "Task graph identity/queue/progress differs");
                            require(capture.taskAllocation().equals(taskAllocator.captureState()), "Restoring tasks consumed IDs");
                            observedCreatures += controllers.size();
                            for (var controller : controllers) {
                                require(controller == creatures.getBoundController(controller.getEntityId()), "Creature cache identity differs");
                                if (staged.getComponent(controller.getEntityId(), CreatureComponent.class) != null)
                                    require(controller == creatures.createController(controller.getEntityId()), "Factory created a duplicate controller");
                            }
                            for (var node : graphState.nodes()) switch(node.payload()) {
                                case TaskGraphSnapshots.Tile p -> tileTaskCount++;
                                case TaskGraphSnapshots.RoomWork p -> roomTaskCount++;
                                case TaskGraphSnapshots.EntityWork p -> entityTaskCount++;
                                case TaskGraphSnapshots.ObjectiveWork p -> objectiveTaskCount++;
                                case TaskGraphSnapshots.Decorator p -> decoratorCount++;
                            }
                            var partyState = PartySnapshots.decode(PartySnapshots.encode(capture.parties()));
                            creatures.restoreParties(partyState);
                            require(partyState.equals(creatures.captureParties()), "Party members/leader/indexes/allocator differ");
                            observedParties += partyState.nodes().size();spawnedParties += partyState.spawned().size();
                            for (var entry : partyState.current().entrySet()) {
                                var node = partyState.nodes().get(entry.getValue());
                                if (partyState.spawned().containsKey(node.id())) require(creatures.getParty(entry.getKey()) == creatures.getPartyById(node.id()), "Shared party identity lost");
                            }
                            var registryStates = new EnumMap<TriggerRegistrySnapshots.Kind,TriggerRegistrySnapshots.State>(TriggerRegistrySnapshots.Kind.class);
                            for (var entry : capture.triggerRegistries().entrySet()) registryStates.put(entry.getKey(), TriggerRegistrySnapshots.decode(TriggerRegistrySnapshots.encode(entry.getValue())));
                            var doors = DoorsController.restoreBindings(kwd, staged, kwd.getVariables(), map, gameRef, level, objects);
                            var beforeTriggerLevel = LevelState.capture(level);
                            var beforeTriggerKeepers = stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList();
                            try (var registries = toniarts.openkeeper.game.trigger.TriggerRegistries.restore(registryStates, gameRef, level,
                                    new GameTimeController(capture.clock()), map, creatures, objects, doors, noDeathCleanup, null, staged)) {
                                require(registryStates.equals(registries.captureState()), "Concrete trigger owner bindings differ");
                                require(savedRandom.equals(random.captureState()), "World bindings consumed random draws");
                                require(objects.getRandom() == random && creatures.getRandom() == random, "Factories do not share restored RNG");
                                var randomTwin = toniarts.openkeeper.game.random.SimulationRandom.restore(savedRandom);
                                var randomCopy = toniarts.openkeeper.game.random.SimulationRandom.restore(savedRandom);
                                for (int draw=0;draw<128;draw++) require(randomTwin.nextLong()==randomCopy.nextLong(), "RNG continuation differs");
                                registries.start();registries.start();
                                require(registryStates.equals(registries.captureState()), "Trigger activation changed execution state");
                                require(beforeTriggerLevel.equals(LevelState.capture(level)), "Trigger binding changed level values");
                                require(beforeTriggerKeepers.equals(stagedPlayers.stream().map(p -> p.getKeeper().captureState()).toList()), "Trigger binding changed keeper values");
                                require(Arrays.equals(beforeHands, EntitySnapshots.encode(staged.capture())), "Trigger registry binding changed entities");
                                triggerOwners += registryStates.values().stream().mapToInt(state -> state.entries().size()).sum();
                            }
                            var creatureAi = CreatureSystemSnapshots.decode(CreatureSystemSnapshots.encode(capture.creatureAi()));
                            var aiSystem = toniarts.openkeeper.game.logic.CreatureAiSystem.restoreState(staged, creatures, tasks, creatureAi);
                            try {require(creatureAi.equals(aiSystem.captureState()), "Active creature membership differs");}
                            finally {aiSystem.stop();}
                        } finally {tasks.stop();}
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
                        System.out.println("PASS " + name + " round=" + round + " keepers=" + keepers.size() + " hands=" + hands.size() + " rooms=" + capture.rooms().size() + " objects=" + objectCount + " books=" + books.entries().size() + " chickens=" + chickenState.controllers().size() + " creatureStates=" + capture.creatureControllers().size() + " controls=" + capture.players().size() + " flashes=" + capture.flashes().size() + " heartPlugs=" + animationState.heart().plugs().size() + " heartDone=" + animationState.heart().done() + " observedHealth=" + healthState.observedHealth().size() + " pendingHealth=" + healthState.dirtyHealth().size() + " observedRecovery=" + healthState.observedRecuperating().size() + " observedSlaps=" + slapState.startTimes().size() + " observedDeaths=" + lifetimeState.death().observed().size() + " hatcheryEntrances=" + spawningState.entrances().size() + " coops=" + spawningState.coops().size() + " freeRange=" + spawningState.owners().size() + " recruitmentEntrances=" + recruitmentState.entrances().size() + " economyPlayers=" + economyState.players().size() + " manaSources=" + manaState.sources().size() + " experienceTimers=" + experienceState.workingTime().size() + " statOwners=" + capture.statistics().size() + " levelBytes=" + LevelSnapshots.encode(levelState).length
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
        System.out.println("PASS creature cache/controller round trips=" + observedCreatures + " (actual executable graph bindings)");
        require(spawnedParties > 0, "No spawned parties exercised");
        require(tileTaskCount > 0, "No live tile tasks exercised");
        System.out.println("PASS tile task node round trips=" + tileTaskCount);
        require(roomTaskCount > 0, "No live room tasks exercised");
        System.out.println("PASS room task node round trips=" + roomTaskCount);
        require(entityTaskCount > 0, "No live entity tasks exercised");
        System.out.println("PASS entity task node round trips=" + entityTaskCount);
        require(objectiveTaskCount > 0, "No live objective tasks exercised");
        System.out.println("PASS objective task nodes=" + objectiveTaskCount + " decorators=" + decoratorCount);
        System.out.println("PASS party node round trips=" + observedParties + " spawned index entries=" + spawnedParties);
        require(triggerOwners > 0, "No concrete trigger owners exercised");
        System.out.println("PASS concrete trigger owner bindings=" + triggerOwners);
        require(triggerTrees > 0, "No trigger trees exercised");
        System.out.println("PASS trigger execution trees=" + triggerTrees + " nodes=" + triggerNodes);
        require(movingBodies > 0, "No active steering bodies exercised");
        System.out.println("PASS movement body round trips=" + movingBodies);
        System.out.println("PASS 12 live owner captures; keeper round trips=" + ownerCount + " carried entity round trips=" + pickedUp);
        System.out.println("Keeper/hand/level/time/statistics/map/room/player/flash/creature-index/spellbook-index/object/chicken/creature-cache/AI-membership/party/unified-task-graph/task-manager/mana/experience/economy/recruitment/chicken-spawning/lifetimes/slaps/health/animation/view/mood/doors/loose-objects/hauling/bridge/movement/trigger-tree/trigger-registry bindings and listener activation only. Whole-world restoration, resumed gameplay and complete save files remain unverified.");
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
