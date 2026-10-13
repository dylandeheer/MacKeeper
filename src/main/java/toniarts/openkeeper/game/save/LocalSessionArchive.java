/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.util.*;
import java.util.stream.Collectors;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.utils.Point;

/** One local server and its observed client, taken at one simulation boundary. */
public record LocalSessionArchive(boolean campaign, SimulationArchive server, ClientState client) {
    public LocalSessionArchive {
        Objects.requireNonNull(server);Objects.requireNonNull(client);
        if (client.model().playerId() != Player.KEEPER1_ID)
            throw new IllegalArgumentException("Local sessions require keeper one");
        var branch = client.progress().campaign();
        if (!campaign && branch != null) throw new IllegalArgumentException("Campaign progress in a non-campaign session");
        if (branch != null) {
            String filename = (branch.type().name() + (branch.level() == 0 ? "" : branch.level()) + branch.variation() + ".kwd").toLowerCase(Locale.ROOT);
            String path = server.definition().source().mapPath();
            if (!path.substring(path.lastIndexOf('/') + 1).equals(filename))
                throw new IllegalArgumentException("Campaign branch differs from saved map");
        }
        var world = server.simulation();
        var players = world.keepers().stream().map(k -> k.id()).collect(Collectors.toSet());
        if (!players.equals(client.model().keepers().stream().map(k -> k.id()).collect(Collectors.toSet())))
            throw new IllegalArgumentException("Client and server keeper rosters differ");
        if (!Set.of(Player.KEEPER1_ID).containsAll(world.transitions().pendingPlayers()))
            throw new IllegalArgumentException("Local transition belongs to another player");
        if (!world.transitions().pendingPlayers().isEmpty()
                && client.model().pending().events().stream().noneMatch(PresentationEvent.Transition.class::isInstance)
                && (client.active() == null || client.active().camera().sweeps().stream().noneMatch(sweep -> sweep.acknowledgeSession())))
            throw new IllegalArgumentException("Pending server transition has no client completion source");
        var map = world.systems().positions();var fog = client.fog().knowledge();
        if (map.width() != fog.width() || map.height() != fog.height())
            throw new IllegalArgumentException("Client and server map dimensions differ");
        long next = world.entities().nextEntityId();
        client.worldView().flowers().keySet().forEach(id -> entity(id, next));
        client.fog().seenEnemies().forEach(id -> entity(id, next));
        client.fog().observedHearts().forEach(id -> entity(id, next));
        entity(client.fog().possessedCreature(), next);
        if (client.active() != null) {
            client.active().cards().creatures().forEach(value -> entity(value.id(), next));
            client.active().hand().items().forEach(value -> entity(value.entityId(), next));
        }
        var observed = client.progress().result();
        var result = world.results().results().get(client.model().playerId());
        if (observed != null) outcome(result, (Boolean) observed.values().get(GameResult.ResultType.LEVEL_WON),
                ((Number) observed.values().get(GameResult.ResultType.TIME_TAKEN)).doubleValue());
        for (var event : client.model().pending().events()) {
            switch (event) {
                case PresentationEvent.ChangedTiles e -> tiles(e.points(), map.width(), map.height());
                case PresentationEvent.RevealedTiles e -> {player(e.keeperId(), players);tiles(e.points(), map.width(), map.height());}
                case PresentationEvent.FlashedTiles e -> {player(e.keeperId(), players);tiles(e.points(), map.width(), map.height());}
                case PresentationEvent.Built e -> {player(e.keeperId(), players);tiles(e.points(), map.width(), map.height());}
                case PresentationEvent.Sold e -> {player(e.keeperId(), players);tiles(e.points(), map.width(), map.height());}
                case PresentationEvent.FogDisabled e -> player(e.keeperId(), players);
                case PresentationEvent.FogReset e -> player(e.keeperId(), players);
                case PresentationEvent.Gold e -> player(e.keeperId(), players);
                case PresentationEvent.Mana e -> player(e.keeperId(), players);
                case PresentationEvent.ResearchUpdate e -> player(e.keeperId(), players);
                case PresentationEvent.ZoomEntity e -> entity(e.entityId(), next);
                case PresentationEvent.Flower e -> entity(e.entityId(), next);
                case PresentationEvent.Possession e -> entity(e.target(), next);
                case PresentationEvent.EnemySighted e -> entity(e.entityId(), next);
                case PresentationEvent.Ended e -> outcome(result, e.win(), e.elapsedSeconds());
                // Camera points can be outside the map; presentation clocks and keeper values may lag.
                default -> { }
            }
        }
    }
    /** Resolve and verify the saved map against the current installation before touching a live session. */
    public IKwdFile resolveDefinition(java.nio.file.Path installedRoot) throws java.io.IOException {
        var root = installedRoot.toRealPath();
        var map = server.definition().source().resolve(root);
        try {
            IKwdFile definition = toniarts.openkeeper.tools.convert.map.KwdFile.load(root.toString(), map);
            validateDefinition(definition);
            return definition;
        } catch (RuntimeException failure) {
            throw new java.io.IOException("Cannot bind saved game to installed map data", failure);
        }
    }
    /** Detached definition binding; renderer asset validation still precedes publication of a loaded client. */
    public void validateDefinition(IKwdFile definition) {
        if (!server.definition().equals(SimulationArchive.Definition.capture(definition)))
            throw new IllegalArgumentException("Saved session requires different map data or settings");
        client.model().bind(definition);
        client.worldView().flowers().values().forEach(flower -> flower.validateDefinition(definition));
        if (!client.sound().levelCategory().equals(Objects.requireNonNullElse(definition.getGameLevel().getSoundCategory(), "")))
            throw new IllegalArgumentException("Saved audio belongs to another level");
        var active = client.active();
        if (active != null) {
            active.interaction().validateDefinitions(definition);active.hand().validateDefinitions(definition);
            if (definition.getImp().getCreatureId() != active.cards().workerId()) throw new IllegalArgumentException("Saved worker definition differs");
            for (var creature : active.cards().creatures())
                if (creature.type() != null && definition.getCreature(creature.type()) == null)
                    throw new IllegalArgumentException("Missing observed creature definition");
            for (var group : active.cards().groups()) if (definition.getCreature(group.type()) == null)
                throw new IllegalArgumentException("Missing creature card definition");
        }
    }
    private static void entity(EntityId id, long next) {
        if (id != null && (id.getId() < 0 || id.getId() >= next))
            throw new IllegalArgumentException("Client reference exceeds server entity allocation");
    }
    private static void player(short id, Set<Short> players) {
        if (!players.contains(id)) throw new IllegalArgumentException("Client notification has no keeper");
    }
    private static void tiles(List<Point> points, int width, int height) {
        for (var p : points) if (p.x < 0 || p.y < 0 || p.x >= width || p.y >= height)
            throw new IllegalArgumentException("Client notification tile is outside map");
    }
    private static void outcome(GameResult.State server, boolean win, double elapsed) {
        if (server == null || !Objects.equals(server.values().get(GameResult.ResultType.LEVEL_WON), win)
                || !(server.values().get(GameResult.ResultType.TIME_TAKEN) instanceof Number time) || time.doubleValue() != elapsed)
            throw new IllegalArgumentException("Client outcome differs from server result");
    }
}
