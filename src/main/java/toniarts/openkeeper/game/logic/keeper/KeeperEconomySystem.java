/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.logic.keeper;

import com.jme3.math.Vector2f;
import toniarts.openkeeper.game.save.KeeperEconomySnapshots;
import com.simsilica.es.EntityData;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.utils.WorldUtils;
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
    private final EntityData data;
    private final List<IPlayerController> players;
    private final Map<Short, State> states = new HashMap<>();
    private static final int[][] DIRECTIONS = {{0,-1},{-1,0},{1,0},{0,1}};

    private static final class State {
        double elapsed;
        final Set<Point> knownGold = new HashSet<>();
        Plan plan;
        int unchanged;
        int progress = -1;
    }

    private record Plan(short room, int x, int y, int width, int height) { }

    public KeeperEconomySystem(IGameWorldController world, IKwdFile level, Collection<IPlayerController> players, EntityData data) {
        this.world = world;
        this.data = data;
        this.map = world.getMapController();
        this.level = level;
        this.players = players.stream().sorted(Comparator.comparingInt(p -> p.getKeeper().getId())).toList();
    }

    /** Call inside the simulation capture boundary. Does not discover new deposits or issue commands. */
    public KeeperEconomySnapshots.State captureState() {
        var result = new TreeMap<Short, KeeperEconomySnapshots.Brain>();
        states.forEach((owner, state) -> result.put(owner, new KeeperEconomySnapshots.Brain(state.elapsed,
                state.knownGold, state.plan == null ? null : new KeeperEconomySnapshots.Plan(state.plan.room(),
                        state.plan.x(), state.plan.y(), state.plan.width(), state.plan.height()), state.unchanged, state.progress)));
        return new KeeperEconomySnapshots.State(result);
    }

    public static KeeperEconomySystem restoreState(IGameWorldController world, IKwdFile level,
            Collection<IPlayerController> players, EntityData data, KeeperEconomySnapshots.State saved) {
        var system = new KeeperEconomySystem(world, level, players, data);
        var owners = new HashSet<Short>();
        for (var player : players) if (!owners.add(player.getKeeper().getId())) throw new IllegalArgumentException("Duplicate economy owner");
        var mapData = system.map.getMapData();
        for (var entry : saved.players().entrySet()) {
            if (!owners.contains(entry.getKey())) throw new IllegalArgumentException("Missing economy owner");
            var brain = entry.getValue();
            for (var point : brain.knownGold()) if (mapData.getTile(point) == null) throw new IllegalArgumentException("Remembered deposit outside map");
            var plan = brain.plan();
            if (plan != null && (level.getRoomById(plan.room()) == null || (long) plan.x() + plan.width() > mapData.getWidth()
                    || (long) plan.y() + plan.height() > mapData.getHeight())) throw new IllegalArgumentException("Invalid economy plan binding");
            if (brain.progress() > (long) mapData.getWidth() * mapData.getHeight()) throw new IllegalArgumentException("Invalid economy progress");
            var state = new State();state.elapsed = brain.elapsed();state.knownGold.addAll(brain.knownGold());
            state.plan = plan == null ? null : new Plan(plan.room(), plan.x(), plan.y(), plan.width(), plan.height());
            state.unchanged = brain.unchanged();state.progress = brain.progress();system.states.put(entry.getKey(), state);
        }
        return system;
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
        if (keeper.getGold() <= needed) {
            updateKnownGold(owner, state.knownGold);
            selectIncomeFrontier(owner, connected, keeper.getDungeonHeartLocation(), state.knownGold);
        }

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

    private void selectIncomeFrontier(short owner, Set<Point> connected, Point heart, Set<Point> knownGold) {
        Set<Point> frontier = new HashSet<>();
        for (Point p : connected) for (int[] d : DIRECTIONS) {
            Point candidate = new Point(p.x+d[0],p.y+d[1]);
            var tile = map.getMapData().getTile(candidate);
            if (tile == null || !map.isTaggable(candidate)
                    || (tile.getOwnerId()!=0 && tile.getOwnerId()!=Player.NEUTRAL_PLAYER_ID)
                    || !map.getTerrain(tile).getFlags().contains(Terrain.TerrainFlag.SOLID)) continue;
            frontier.add(candidate);
        }
        Comparator<Point> order = Comparator.comparingLong((Point p)->Math.abs((long)p.x-heart.x)+Math.abs((long)p.y-heart.y))
                .thenComparingInt(p->p.y).thenComparingInt(p->p.x);
        List<Point> deposits = frontier.stream().filter(p->map.getMapData().getTile(p).getGold()>0)
                .sorted(order).toList();
        if (!deposits.isEmpty()) {
            deposits.stream().limit(4).forEach(p->select(p,owner));
            return;
        }
        Point approach = findGoldApproach(owner, connected, knownGold);
        if (approach != null) { select(approach, owner); return; }
        // With no perceived deposit, prospect one ordinary earth tile. This uses only the
        // current frontier, not hidden gold locations. Workers must dig and claim each step.
        // A build rectangle limits room placement, not the search for income.
        if (frontier.stream().filter(p->map.isSelected(p,owner)).count() >= 4) return;
        frontier.stream().filter(p->!map.isSelected(p,owner)
                && !map.getTerrain(map.getMapData().getTile(p)).getFlags().contains(Terrain.TerrainFlag.IMPENETRABLE))
                .min(order).ifPresent(p->select(p,owner));
    }

    private void updateKnownGold(short owner, Set<Point> known) {
        // Match the fog controller: a creature on a closed door contributes no perception.
        Set<Point> closedDoors = new HashSet<>();
        for (var id : data.findEntities(null, Position.class, DoorComponent.class, DoorViewState.class)) {
            if (!data.getComponent(id, DoorViewState.class).open) {
                closedDoors.add(WorldUtils.vectorToPoint(data.getComponent(id, Position.class).position));
            }
        }
        for (var id : data.findEntities(null, CreatureComponent.class, Owner.class, Position.class, Health.class)) {
            Owner ownership = data.getComponent(id, Owner.class);
            if (ownership.ownerId != owner || ownership.controlId != owner || data.getComponent(id, Health.class).health <= 0
                    || data.getComponent(id, Death.class) != null || data.getComponent(id, CreatureImprisoned.class) != null
                    || data.getComponent(id, CreatureTortured.class) != null || data.getComponent(id, InHand.class) != null) continue;
            Creature raw = level.getCreature(data.getComponent(id, CreatureComponent.class).creatureId);
            if (raw == null) continue;
            float range = raw.getAttributes().getPerceptionRange();
            if (!Float.isFinite(range) || range <= 0) continue;
            Point center = WorldUtils.vectorToPoint(data.getComponent(id, Position.class).position);
            if (closedDoors.contains(center)) continue;
            int radius = (int)Math.min(Math.ceil(range), Math.max(map.getMapData().getWidth(), map.getMapData().getHeight()));
            for (int y=Math.max(0,center.y-radius);y<=Math.min(map.getMapData().getHeight()-1,center.y+radius);y++) {
                for (int x=Math.max(0,center.x-radius);x<=Math.min(map.getMapData().getWidth()-1,center.x+radius);x++) {
                    long dx=(long)x-center.x, dy=(long)y-center.y;
                    if (dx*dx+dy*dy >= (double)range*range) continue;
                    var tile=map.getMapData().getTile(x,y);
                    if (tile.getGold()>0 && map.getTerrain(tile).getFlags().contains(Terrain.TerrainFlag.REVEAL_THROUGH_FOG_OF_WAR)) known.add(new Point(x,y));
                }
            }
        }
        known.removeIf(p->map.getMapData().getTile(p)==null || map.getMapData().getTile(p).getGold()==0);
    }

    private Point findGoldApproach(short owner, Set<Point> connected, Set<Point> knownGold) {
        if (knownGold.isEmpty()) return null;
        Map<Point,Point> previous = new HashMap<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        connected.stream().sorted(Comparator.comparingInt((Point p)->p.y).thenComparingInt(p->p.x))
                .forEach(p->{ previous.put(p,null); queue.add(p); });
        while (!queue.isEmpty()) {
            Point p=queue.removeFirst();
            if (knownGold.contains(p)) {
                List<Point> path = new ArrayList<>();
                for (Point step=p;previous.get(step)!=null;step=previous.get(step)) path.add(step);
                Collections.reverse(path);
                for(Point step:path) if(map.isTaggable(step)) return step;
            }
            for (int[] d:DIRECTIONS) {
                Point next=new Point(p.x+d[0],p.y+d[1]);
                if(previous.containsKey(next))continue;
                var tile=map.getMapData().getTile(next);
                if(tile==null || (tile.getOwnerId()!=owner && tile.getOwnerId()!=0 && tile.getOwnerId()!=Player.NEUTRAL_PLAYER_ID))continue;
                var flags=map.getTerrain(tile).getFlags();
                if(flags.contains(Terrain.TerrainFlag.WATER)||flags.contains(Terrain.TerrainFlag.LAVA))continue;
                if(flags.contains(Terrain.TerrainFlag.SOLID)) {
                    if(!map.isTaggable(next) || (flags.contains(Terrain.TerrainFlag.IMPENETRABLE)&&!knownGold.contains(next)))continue;
                } else if(flags.contains(Terrain.TerrainFlag.ROOM) || !flags.contains(Terrain.TerrainFlag.OWNABLE))continue;
                previous.put(next,p);queue.addLast(next);
            }
        }
        return null;
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
