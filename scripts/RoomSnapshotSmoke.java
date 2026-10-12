/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.storage.RoomGoldControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.tools.convert.map.*;

/** All-map staged room binding and matched storage mutations; no restored whole-game loops. */
class RoomSnapshotSmoke {
    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath() + "/";
        List<Path> maps;
        try (var files = Files.list(Path.of(base, "Data/editor/maps"))) {
            maps = files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList();
        }
        int rooms = 0, withdrawals = 0;Set<String> types = new TreeSet<>();
        for (Path path : maps) {
            var definition = KwdFile.load(base, path);var data = new GameEntityData();
            PlayerService service = (PlayerService) Proxy.newProxyInstance(PlayerService.class.getClassLoader(), new Class<?>[]{PlayerService.class},
                    (p, m, a) -> m.getReturnType() == boolean.class ? false : null);
            var game = new GameController(definition, null, data, definition.getVariables(), service);
            try {
                var source = (MapController) game.getGameWorldController().getMapController();
                if (game.getPlayerController((short) 3) != null) game.getGameWorldController().addGold((short) 3, 12_345);
                // Preserve selection and partial damage, not just authored map defaults.
                var tile = source.getMapData().getTile(0, 0);tile.setSelected(true, (short) 3);tile.setHealth(Math.max(0, tile.getHealth() - 1));
                byte[] entities = EntitySnapshots.encode(data.capture());
                byte[] bindings = RoomSnapshots.encode(source.captureRoomStates());
                var staged = GameEntityData.restore(EntitySnapshots.decode(entities));
                try {
                    var keepers = game.getLevelInfo().getPlayers().values().stream().map(k -> Keeper.restoreState(k.captureState(), k.getPlayer())).toList();
                    var level = GameController.restoreLevelInfo(definition, LevelState.capture(game.getLevelInfo()), keepers);
                    var clock = new GameTimeController(((GameTimeController) game.getGameTimer()).captureState());
                    IObjectsController objects = (IObjectsController) Proxy.newProxyInstance(IObjectsController.class.getClassLoader(), new Class<?>[]{IObjectsController.class},
                            (p, m, a) -> {throw new AssertionError("Restore called object controller: " + m.getName());});
                    var restored = MapController.restore(definition, objects, definition.getVariables(), clock, staged, level, RoomSnapshots.decode(bindings));
                    require(Arrays.equals(entities, EntitySnapshots.encode(staged.capture())), "Room bind allocated/changed entities: " + path);
                    require(Arrays.equals(bindings, RoomSnapshots.encode(restored.captureRoomStates())), "Room bindings differ: " + path);
                    require(restored.getMapData().getTile(0, 0).isSelected((short) 3), "Selection reset");
                    for (var room : source.captureRoomStates()) {
                        var original = source.getRoomController(room.entityId());var copy = restored.getRoomController(room.entityId());
                        types.add(copy.getClass().getSimpleName());rooms++;
                        for (var point : room.coordinates()) {
                            require(original.isTileAccessible(null, point) == copy.isTileAccessible(null, point), "Room accessibility changed");
                            for (int[] delta : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                                var from = new toniarts.openkeeper.utils.Point(point.x + delta[0], point.y + delta[1]);
                                require(original.isTileAccessible(from, point) == copy.isTileAccessible(from, point), "Room entrance routing changed");
                            }
                        }
                        if (original.hasObjectControl(ObjectType.GOLD)) {
                            RoomGoldControl from = original.getObjectControl(ObjectType.GOLD), to = copy.getObjectControl(ObjectType.GOLD);
                            if (from.getCurrentCapacity() > 0) {
                                int take = Math.min(501, from.getCurrentCapacity());
                                require(from.removeGold(take) == to.removeGold(take), "Withdrawal result changed");withdrawals++;
                            }
                        }
                    }
                    require(Arrays.equals(EntitySnapshots.encode(data.capture()), EntitySnapshots.encode(staged.capture())), "Storage continuation differs: " + path);
                    require(Arrays.equals(RoomSnapshots.encode(source.captureRoomStates()), RoomSnapshots.encode(restored.captureRoomStates())), "Storage membership/order differs");
                    require(Arrays.equals(bindings, RoomSnapshots.encode(RoomSnapshots.decode(bindings))), "Saved room bytes changed");
                    System.out.println("PASS " + path.getFileName() + " rooms=" + source.captureRoomStates().size() + " roomBytes=" + bindings.length);
                } finally {staged.close();}
            } finally {game.stop();data.close();}
        }
        require(withdrawals > 0, "No stored-gold continuation checked");
        System.out.println("PASS maps=" + maps.size() + " rooms=" + rooms + " matchedWithdrawals=" + withdrawals + " classes=" + types);
        System.out.println("Staged map/room bindings only. Map controls, tasks, world systems and complete resumed games remain unverified.");
    }
    static void require(boolean ok, String message) {if (!ok) throw new AssertionError(message);}
}
