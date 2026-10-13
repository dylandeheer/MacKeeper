/* SPDX-License-Identifier: GPL-3.0-or-later */
// Reads owned game data and converted models. Does not prove mission progression or animation fidelity.
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.common.RoomInstance;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.fogofwar.IFogOfWarInformation;
import toniarts.openkeeper.view.map.RoomFactory;
import toniarts.openkeeper.view.map.WallSection;

class GateCollapseSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: GateCollapseSmoke <original data> <converted assets>");
        String base = Path.of(args[0]).toAbsolutePath() + "/";
        var assets = new DesktopAssetManager(true);
        assets.registerLocator(Path.of(args[1]).toAbsolutePath().toString(), FileLocator.class);
        IFogOfWarInformation visible = (IFogOfWarInformation) Proxy.newProxyInstance(IFogOfWarInformation.class.getClassLoader(),
                new Class<?>[]{IFogOfWarInformation.class}, (proxy, method, parameters) -> true);
        Set<Room.TileConstruction> modelTypes = new HashSet<>();
        int checked = 0, inspectedMaps = 0;
        try (var files = Files.list(Path.of(base, "Data/editor/maps"))) {
            for (Path file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                var kwd = KwdFile.load(base, file);
                var actions = kwd.getTriggers().values().stream()
                        .filter(t -> t instanceof TriggerAction a && a.getType() == TriggerAction.ActionType.COLLAPSE_HERO_GATE).toList();
                if (actions.isEmpty()) continue;
                inspectedMaps++;
                var data = new DefaultEntityData();
                Map<Short, Keeper> keepers = new HashMap<>();
                kwd.getPlayers().forEach((id, player) -> keepers.put(id, new Keeper(player)));
                var level = (ILevelInfo) Proxy.newProxyInstance(ILevelInfo.class.getClassLoader(), new Class<?>[]{ILevelInfo.class},
                        (proxy, method, parameters) -> switch (method.getName()) {
                            case "getPaydayCycle" -> 0L;
                            case "getLevelData" -> kwd;
                            case "getPlayers" -> keepers;
                            case "getPlayer" -> keepers.get((short) parameters[0]);
                            default -> throw new AssertionError(method.getName());
                        });
                var objects = new ObjectsController(kwd, data, kwd.getVariables(), () -> 0, null, level);
                var map = new MapController(kwd, objects, kwd.getVariables(), () -> 0, data, level);
                for (Trigger action : actions) {
                    var p = new Point(action.<Number>getUserData("posX").intValue() - 1,
                            action.<Number>getUserData("posY").intValue() - 1);
                    var gate = map.getRoomControllerByCoordinates(p);
                    if (gate == null) {
                        var neighbors = Arrays.stream(toniarts.openkeeper.utils.WorldUtils.getSurroundingTiles(map.getMapData(), p, false))
                                .map(map::getRoomControllerByCoordinates).filter(Objects::nonNull)
                                .filter(r -> r.getRoom().getTileConstruction() == Room.TileConstruction.HERO_GATE_3_BY_1).distinct().toList();
                        if (neighbors.size() != 1) throw new AssertionError("Ambiguous/missing gate at " + p + " in " + file.getFileName());
                        gate = neighbors.getFirst();
                        System.out.println("WALL TARGET " + file.getFileName() + " trigger=" + action.getId() + " at " + p);
                    }
                    if (!gate.getRoom().isHeroGate()) throw new AssertionError("Invalid gate target in " + file.getFileName());
                    var coordinates = List.copyOf(gate.getRoomInstance().getCoordinates());
                    boolean wasDestroyed = gate.isDestroyed();
                    if (map.collapseHeroGate(p) == wasDestroyed) throw new AssertionError("Wrong collapse result");
                    if (!gate.isDestroyed() || !gate.getRoomInstance().isDestroyed() || gate.getHealth() != 0) throw new AssertionError("State mismatch");
                    if (map.collapseHeroGate(p)) throw new AssertionError("Repeated collapse changed state");
                    for (Point tile : coordinates) {
                        if (!gate.getEntityId().equals(map.getMapData().getTile(tile).getRoomId())) throw new AssertionError("Lost footprint");
                        if (map.getMapData().getTile(tile).getHealth() != 0) throw new AssertionError("Tile health mismatch");
                    }
                    if (modelTypes.add(gate.getRoom().getTileConstruction())) {
                        Thing.Room placement = kwd.getThings(Thing.Room.class).stream()
                                .filter(t -> coordinates.contains(new Point(t.getPosX(), t.getPosY()))).findFirst().orElse(null);
                        var rendered = new RoomInstance(gate.getRoom(), placement);
                        for (Point tile : coordinates) rendered.addCoordinate(tile);
                        rendered.setDestroyed(true);
                        // Exercise every wall piece as well as the floor; this is model loading, not layout verification.
                        rendered.setWallSections(List.of(new WallSection(WallSection.WallDirection.NORTH, coordinates),
                                new WallSection(WallSection.WallDirection.SOUTH, coordinates),
                                new WallSection(WallSection.WallDirection.EAST, List.of(coordinates.getFirst())),
                                new WallSection(WallSection.WallDirection.WEST, List.of(coordinates.getLast()))));
                        RoomFactory.constructRoom(rendered, assets, kwd).construct(visible);
                        System.out.println("PASS destroyed models: " + gate.getRoom().getTileConstruction());
                    }
                    checked++;
                }
                System.out.println("PASS " + file.getFileName() + ": " + actions.size() + " collapse commands");
            }
        }
        if (checked == 0 || modelTypes.size() != 2) throw new AssertionError("Expected collapse commands and both shipped gate types");
        System.out.println("PASS " + checked + " commands across " + inspectedMaps + " maps; mission progression and animation/audio not tested");
    }
}
