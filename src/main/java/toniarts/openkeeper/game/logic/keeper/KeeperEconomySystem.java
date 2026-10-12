/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.logic.keeper;

import com.jme3.math.Vector2f;
import java.util.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.Point;

/** Server-side economy decisions. Excavation and claiming remain ordinary worker tasks. */
public final class KeeperEconomySystem implements IGameLogicUpdatable {
    private final IGameWorldController world;
    private final IMapController map;
    private final IKwdFile level;
    private final List<IPlayerController> players;
    private final Map<Short, State> states = new HashMap<>();
    private static final int[][] DIRECTIONS = {{0,-1},{-1,0},{1,0},{0,1}};

    private static final class State {
        double elapsed;
        Plan plan;
        int unchanged;
        int progress = -1;
    }
    private record Plan(short room, int x, int y, int width, int height) { }

    public KeeperEconomySystem(IGameWorldController world, IKwdFile level, Collection<IPlayerController> players) {
        this.world = world;
        this.map = world.getMapController();
        this.level = level;
        this.players = players.stream().sorted(Comparator.comparingInt(p -> p.getKeeper().getId())).toList();
    }

    @Override public void processTick(float tpf) {
        if (!Float.isFinite(tpf) || tpf <= 0) return;
        synchronized (GameWorldController.GOLD_LOCK) {
            for (var player : players) {
                Keeper keeper = player.getKeeper();
                if (!keeper.isAi() || keeper.getId() < Player.KEEPER1_ID || keeper.isDestroyed()
                        || player.getRoomControl().getDungeonHeart() == null) {
                    states.remove(keeper.getId());
                    continue;
                }
                AI profile = keeper.getPlayer() == null ? null : keeper.getPlayer().getAiAttributes();
                if (profile == null) continue;
                State state = states.computeIfAbsent(keeper.getId(), ignored -> new State());
                state.elapsed += tpf;
                // Authored speed is used as a decision delay. Original scheduling parity is unverified.
                double interval = Math.max(1, profile.getSpeed());
                if (state.elapsed < interval) continue;
                state.elapsed %= interval; // No burst of commands after a delayed frame.
                think(player, profile, state);
            }
        }
    }

    private void think(IPlayerController player, AI profile, State state) {
        Keeper keeper = player.getKeeper();
        short owner = keeper.getId();
        Set<Point> connected = connectedOwnedTiles(keeper.getDungeonHeartLocation(), owner);
        if (connected.isEmpty()) return;
        List<Room> rooms = buildOrder(keeper, profile);
        Set<Point> blocked = world.getConstructionBlockingTiles();
        if (state.plan != null) {
            Room room = level.getRoomById(state.plan.room());
            if (!rooms.contains(room) || !usable(state.plan, owner, profile, blocked)) state.plan = null;
        }
        // Income can proceed while workers prepare a room or wait for its price.
        long needed = profile.getMineGoldUntilGoldHeldIsGreaterThan();
        if (state.plan != null) needed = Math.max(needed, cost(state.plan));
        if (keeper.getGold() <= needed) selectIncomeFrontier(owner, profile, connected);

        if (state.plan == null) {
            for (Room room : rooms) {
                if (player.getRoomControl().getTypeCount(room) > 0) continue;
                state.plan = findPlan(room, owner, profile, connected, keeper.getDungeonHeartLocation(), blocked);
                if (state.plan != null) break;
            }
            state.progress = -1;
            state.unchanged = 0;
        }
        if (state.plan == null) return;
        Plan plan = state.plan;
        int ready = 0;
        for (Point p : points(plan)) {
            if (map.isBuildable(p, owner, plan.room())) ready++;
            else if (map.isTaggable(p) && adjacentTo(p, connected)) select(p, owner);
        }
        if (ready == plan.width() * plan.height() && keeper.getGold() >= cost(plan)) {
            world.build(new Vector2f(plan.x(), plan.y()),
                    new Vector2f(plan.x()+plan.width()-1, plan.y()+plan.height()-1), owner, plan.room());
            // The world revalidates entities, ownership and funds. A rejected build may be retried.
            if (player.getRoomControl().getTypeCount(level.getRoomById(plan.room())) > 0) state.plan = null;
        }
        if (ready != state.progress) { state.progress = ready; state.unchanged = 0; }
        else if (++state.unchanged >= 60) state.plan = null;
    }

    private List<Room> buildOrder(Keeper keeper, AI profile) {
        Set<Short> discovered = new HashSet<>();
        for (var entry : keeper.getAvailableRooms()) if (entry.isDiscovered()) discovered.add(entry.getId());
        List<Room> result = new ArrayList<>();
        if (profile.getBuildOrder() == null) return result;
        for (short id : profile.getBuildOrder()) {
            Room room = level.getRoomById(id);
            if (room != null && discovered.contains(id) && room.getFlags().contains(Room.RoomFlag.BUILDABLE)
                    && room.getFlags().contains(Room.RoomFlag.PLACEABLE_ON_LAND) && !result.contains(room)) result.add(room);
        }
        return result;
    }

    private Plan findPlan(Room room, short owner, AI profile, Set<Point> connected, Point heart, Set<Point> blocked) {
        int size = Math.max(9, profile.getDefaultSize());
        int width = Math.max(3, (int)Math.sqrt(size));
        int height = (size + width - 1) / width;
        Plan best = null;
        long bestScore = Long.MAX_VALUE;
        // Candidate origins come from owned frontier tiles, bounded by the map rather than a global path search.
        Set<Point> origins = new HashSet<>();
        for (Point p : connected) for (int dx=-width; dx<=1; dx++) for (int dy=-height; dy<=1; dy++) {
            origins.add(new Point(p.x+dx,p.y+dy));
        }
        for (Point origin : origins) {
            Plan plan = new Plan(room.getRoomId(), origin.x, origin.y, width, height);
            if (!usable(plan, owner, profile, blocked)) continue;
            boolean connectedToHeart = false;
            int excavation = 0;
            for (Point p : points(plan)) {
                connectedToHeart |= connected.contains(p) || adjacentTo(p, connected);
                if (!map.isBuildable(p, owner, room.getRoomId())) excavation++;
            }
            if (!connectedToHeart) continue;
            long distance = Math.abs((long)origin.x-heart.x) + Math.abs((long)origin.y-heart.y);
            long score = excavation * 10000L + distance * 100L;
            if (score < bestScore || (score == bestScore && (best == null || origin.y < best.y()
                    || (origin.y == best.y() && origin.x < best.x())))) { best=plan; bestScore=score; }
        }
        return best;
    }

    private boolean usable(Plan plan, short owner, AI profile, Set<Point> blocked) {
        for (Point p : points(plan)) {
            var tile = map.getMapData().getTile(p);
            if (tile == null || blocked.contains(p) || !insideBuildArea(p, profile)) return false;
            Terrain terrain = map.getTerrain(tile);
            if (terrain.getFlags().contains(Terrain.TerrainFlag.ROOM)
                    || terrain.getFlags().contains(Terrain.TerrainFlag.WATER)
                    || terrain.getFlags().contains(Terrain.TerrainFlag.LAVA)) return false;
            if (tile.getOwnerId() != owner && tile.getOwnerId() != Player.NEUTRAL_PLAYER_ID && tile.getOwnerId() != 0) return false;
            if (terrain.getFlags().contains(Terrain.TerrainFlag.SOLID)) {
                if (!map.isTaggable(p) || terrain.getFlags().contains(Terrain.TerrainFlag.IMPENETRABLE)) return false;
            } else if (!terrain.getFlags().contains(Terrain.TerrainFlag.OWNABLE)) return false;
        }
        return true;
    }

    private boolean insideBuildArea(Point p, AI ai) {
        // All-zero bounds in skirmish profiles mean no custom build rectangle.
        if (ai.getBuildAreaStartX()==0 && ai.getBuildAreaStartY()==0
                && ai.getBuildAreaEndX()==0 && ai.getBuildAreaEndY()==0) return true;
        return p.x>=ai.getBuildAreaStartX() && p.y>=ai.getBuildAreaStartY()
                && p.x<=ai.getBuildAreaEndX() && p.y<=ai.getBuildAreaEndY();
    }

    private Set<Point> connectedOwnedTiles(Point heart, short owner) {
        Set<Point> result = new HashSet<>();
        if (heart == null) return result;
        ArrayDeque<Point> pending = new ArrayDeque<>();
        pending.add(heart);
        while (!pending.isEmpty()) {
            Point p=pending.removeFirst();
            IMapTileInformation tile=map.getMapData().getTile(p);
            if (tile==null || tile.getOwnerId()!=owner || map.getTerrain(tile).getFlags().contains(Terrain.TerrainFlag.SOLID)
                    || map.getTerrain(tile).getFlags().contains(Terrain.TerrainFlag.WATER)
                    || map.getTerrain(tile).getFlags().contains(Terrain.TerrainFlag.LAVA) || !result.add(p)) continue;
            for (int[] d:DIRECTIONS) pending.add(new Point(p.x+d[0],p.y+d[1]));
        }
        return result;
    }

    private void selectIncomeFrontier(short owner, AI profile, Set<Point> connected) {
        List<Point> frontier = new ArrayList<>();
        for (Point p : connected) for (int[] d : DIRECTIONS) {
            Point candidate = new Point(p.x+d[0],p.y+d[1]);
            var tile = map.getMapData().getTile(candidate);
            if (tile == null || !insideBuildArea(candidate,profile) || !map.isTaggable(candidate)
                    || (tile.getOwnerId()!=0 && tile.getOwnerId()!=Player.NEUTRAL_PLAYER_ID)) continue;
            // Only exposed deposits: do not select remote gold through unexplored solid terrain.
            if (tile.getGold()>0) frontier.add(candidate);
        }
        frontier.stream().distinct().sorted(Comparator.comparingInt((Point p)->p.y).thenComparingInt(p->p.x))
                .limit(4).forEach(p->select(p,owner));
    }

    private void select(Point p, short owner) {
        if (!map.isSelected(p,owner)) map.selectTiles(new Vector2f(p.x,p.y), new Vector2f(p.x,p.y), true,owner);
    }
    private static boolean adjacentTo(Point p, Set<Point> points) {
        for (int[] d:DIRECTIONS) if (points.contains(new Point(p.x+d[0],p.y+d[1]))) return true;
        return false;
    }
    private long cost(Plan p) { return (long)p.width()*p.height()*level.getRoomById(p.room()).getCost(); }
    private static List<Point> points(Plan p) {
        List<Point> result = new ArrayList<>(p.width()*p.height());
        for(int y=p.y();y<p.y()+p.height();y++) for(int x=p.x();x<p.x()+p.width();x++) result.add(new Point(x,y));
        return result;
    }
    @Override public void start() { }
    @Override public void stop() { states.clear(); }
}
