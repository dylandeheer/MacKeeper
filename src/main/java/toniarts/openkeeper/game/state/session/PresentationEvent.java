/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.data.KeeperState;
import toniarts.openkeeper.tools.convert.map.TriggerAction;
import toniarts.openkeeper.utils.Point;

/** Immutable runtime client notifications; engine objects and callbacks never enter a saved event. */
public sealed interface PresentationEvent {
    int MAX_TILES = 1_000_000;
    interface Target extends GameSessionListener {void onEnemySighted(EntityId entityId);}
    enum ResearchAction {ADDED, REMOVED, CHANGED}
    enum Kind {CHANGEDTILES, REVEALEDTILES, FOGDISABLED, FOGRESET, GOLD, MANA, BUILT, SOLD, PAUSED, RESUMED, WIDESCREEN, ENDED, SPEECH, TRANSITION, FLASHBUTTON, ROTATE, MESSAGE, OBJECTIVE, ZOOMPOINT, FLASHEDTILES, ZOOMENTITY, FLOWER, RESEARCHUPDATE, POSSESSION, ENEMYSIGHTED}
    record Vec3(float x, float y, float z) {
        public Vec3 {if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw new IllegalArgumentException("Invalid presentation position");}
        public static Vec3 of(Vector3f value) {return new Vec3(value.x, value.y, value.z);}
        public Vector3f vector() {return new Vector3f(x, y, z);}
    }
    void deliver(Target target);
    default Kind kind() {
        return switch (this) {
            case ChangedTiles event -> Kind.CHANGEDTILES;
            case RevealedTiles event -> Kind.REVEALEDTILES;
            case FogDisabled event -> Kind.FOGDISABLED;
            case FogReset event -> Kind.FOGRESET;
            case Gold event -> Kind.GOLD;
            case Mana event -> Kind.MANA;
            case Built event -> Kind.BUILT;
            case Sold event -> Kind.SOLD;
            case Paused event -> Kind.PAUSED;
            case Resumed event -> Kind.RESUMED;
            case Widescreen event -> Kind.WIDESCREEN;
            case Ended event -> Kind.ENDED;
            case Speech event -> Kind.SPEECH;
            case Transition event -> Kind.TRANSITION;
            case FlashButton event -> Kind.FLASHBUTTON;
            case Rotate event -> Kind.ROTATE;
            case Message event -> Kind.MESSAGE;
            case Objective event -> Kind.OBJECTIVE;
            case ZoomPoint event -> Kind.ZOOMPOINT;
            case FlashedTiles event -> Kind.FLASHEDTILES;
            case ZoomEntity event -> Kind.ZOOMENTITY;
            case Flower event -> Kind.FLOWER;
            case ResearchUpdate event -> Kind.RESEARCHUPDATE;
            case Possession event -> Kind.POSSESSION;
            case EnemySighted event -> Kind.ENEMYSIGHTED;
        };
    }
    record ChangedTiles(List<Point> points) implements PresentationEvent {
        public ChangedTiles {points = tiles(points);}
        public void deliver(Target receiver) {receiver.onTilesChange(points);}
    }
    record RevealedTiles(List<Point> points, boolean explore, short keeperId) implements PresentationEvent {
        public RevealedTiles {points = tiles(points);player(keeperId);}
        public void deliver(Target receiver) {receiver.onTilesReveal(points, explore, keeperId);}
    }
    record FogDisabled(short keeperId) implements PresentationEvent {
        public FogDisabled {player(keeperId);}
        public void deliver(Target receiver) {receiver.onFogOfWarDisabled(keeperId);}
    }
    record FogReset(short keeperId) implements PresentationEvent {
        public FogReset {player(keeperId);}
        public void deliver(Target receiver) {receiver.onFogOfWarReset(keeperId);}
    }
    record Gold(short keeperId, long gold, int reserve) implements PresentationEvent {
        public Gold {player(keeperId);}
        public void deliver(Target receiver) {receiver.onGoldChange(keeperId, gold, reserve);}
    }
    record Mana(short keeperId, int mana, int loose, int gain) implements PresentationEvent {
        public Mana {player(keeperId);}
        public void deliver(Target receiver) {receiver.onManaChange(keeperId, mana, loose, gain);}
    }
    record Built(short keeperId, List<Point> points) implements PresentationEvent {
        public Built {points = tiles(points);player(keeperId);}
        public void deliver(Target receiver) {receiver.onBuild(keeperId, points);}
    }
    record Sold(short keeperId, List<Point> points) implements PresentationEvent {
        public Sold {points = tiles(points);player(keeperId);}
        public void deliver(Target receiver) {receiver.onSold(keeperId, points);}
    }
    record Paused() implements PresentationEvent {
        public void deliver(Target receiver) {receiver.onGamePaused();}
    }
    record Resumed() implements PresentationEvent {
        public void deliver(Target receiver) {receiver.onGameResumed();}
    }
    record Widescreen(boolean enabled) implements PresentationEvent {
        public void deliver(Target receiver) {receiver.onSetWidescreen(enabled);}
    }
    record Ended(boolean win, double elapsedSeconds) implements PresentationEvent {
        public Ended {if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) throw new IllegalArgumentException("Invalid result time");}
        public void deliver(Target receiver) {receiver.onGameEnded(win, elapsedSeconds);}
    }
    record Speech(int speechId, boolean showText, boolean introduction, int pathId) implements PresentationEvent {
        public void deliver(Target receiver) {receiver.onPlaySpeech(speechId, showText, introduction, pathId);}
    }
    record Transition(short pathId, Vec3 start) implements PresentationEvent {
        public Transition {Objects.requireNonNull(start);}
        public void deliver(Target receiver) {receiver.onDoTransition(pathId, start.vector());}
    }
    record FlashButton(TriggerAction.MakeType buttonType, short targetId, TriggerAction.ButtonType targetButtonType, boolean enabled, int time) implements PresentationEvent {
        public void deliver(Target receiver) {receiver.onFlashButton(buttonType, targetId, targetButtonType, enabled, time);}
    }
    record Rotate(Vec3 point, boolean relative, int angle, int time) implements PresentationEvent {
        public Rotate {Objects.requireNonNull(point);}
        public void deliver(Target receiver) {receiver.onRotateViewAroundPoint(point.vector(), relative, angle, time);}
    }
    record Message(int textId) implements PresentationEvent {
        public void deliver(Target receiver) {receiver.onShowMessage(textId);}
    }
    record Objective(int objectiveId) implements PresentationEvent {
        public void deliver(Target receiver) {receiver.onShowObjective(objectiveId);}
    }
    record ZoomPoint(Vec3 point) implements PresentationEvent {
        public ZoomPoint {Objects.requireNonNull(point);}
        public void deliver(Target receiver) {receiver.onZoomViewToPoint(point.vector());}
    }
    record FlashedTiles(List<Point> points, boolean enabled, short keeperId) implements PresentationEvent {
        public FlashedTiles {points = tiles(points);player(keeperId);}
        public void deliver(Target receiver) {receiver.onTileFlash(points, enabled, keeperId);}
    }
    record ZoomEntity(EntityId entityId) implements PresentationEvent {
        public ZoomEntity {entity(entityId);}
        public void deliver(Target receiver) {receiver.onZoomViewToEntity(entityId);}
    }
    record Flower(EntityId entityId, int interval) implements PresentationEvent {
        public Flower {entity(entityId);}
        public void deliver(Target receiver) {receiver.onShowUnitFlower(entityId, interval);}
    }
    record ResearchUpdate(ResearchAction action, short keeperId, KeeperState.Research research) implements PresentationEvent {
        public ResearchUpdate {player(keeperId);Objects.requireNonNull(action);Objects.requireNonNull(research);}
        public void deliver(Target target) {
            var value = research.restore();
            switch (action) {
                case ADDED -> target.onEntityAdded(keeperId, value);
                case REMOVED -> target.onEntityRemoved(keeperId, value);
                case CHANGED -> target.onResearchStatusChanged(keeperId, value);
            }
        }
    }
    record Possession(EntityId target) implements PresentationEvent {
        public Possession {if (target != null) entity(target);}
        public void deliver(Target receiver) {receiver.setPossession(target);}
    }
    record EnemySighted(EntityId entityId) implements PresentationEvent {
        public EnemySighted {entity(entityId);}
        public void deliver(Target receiver) {receiver.onEnemySighted(entityId);}
    }
    private static List<Point> tiles(List<Point> points) {
        if (points.size() > MAX_TILES) throw new IllegalArgumentException("Too many presentation tiles");
        return List.copyOf(points); // Point is immutable.
    }
    private static void player(short id) {if (id < 0 || id > 255) throw new IllegalArgumentException("Invalid presentation player");}
    private static void entity(EntityId id) {if (id == null || id.getId() < 0) throw new IllegalArgumentException("Invalid presentation entity");}
}
