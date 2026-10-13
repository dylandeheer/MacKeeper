/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.tools.convert.map.TriggerAction;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.state.session.PresentationEvent.*;

/** Copy notifications at receipt, then deliver them on the render thread in receipt order. */
public final class QueuedGameSessionListener implements PresentationEvent.Target {
    private final PresentationQueue queue;
    private final GameSessionListener lifecycle;
    public QueuedGameSessionListener(PresentationQueue queue, GameSessionListener lifecycle) {
        this.queue = Objects.requireNonNull(queue);this.lifecycle = Objects.requireNonNull(lifecycle);
    }
    public void onLoadFailed(String message) {lifecycle.onLoadFailed(message);}
    public void onGameDataLoaded(Collection<Keeper> players) {lifecycle.onGameDataLoaded(players);}
    public void onLoadComplete(short keeperId) {lifecycle.onLoadComplete(keeperId);}
    public void onLoadStatusUpdate(float progress, short keeperId) {lifecycle.onLoadStatusUpdate(progress, keeperId);}
    public void onGameStarted() {lifecycle.onGameStarted();}
    public void onTilesChange(List<Point> points) {queue.add(new ChangedTiles(points));}
    public void onTilesReveal(List<Point> points, boolean explore, short keeperId) {queue.add(new RevealedTiles(points, explore, keeperId));}
    public void onFogOfWarDisabled(short keeperId) {queue.add(new FogDisabled(keeperId));}
    public void onFogOfWarReset(short keeperId) {queue.add(new FogReset(keeperId));}
    public void onGoldChange(short keeperId, long gold, int reserve) {queue.add(new Gold(keeperId, gold, reserve));}
    public void onManaChange(short keeperId, int mana, int loose, int gain) {queue.add(new Mana(keeperId, mana, loose, gain));}
    public void onBuild(short keeperId, List<Point> points) {queue.add(new Built(keeperId, points));}
    public void onSold(short keeperId, List<Point> points) {queue.add(new Sold(keeperId, points));}
    public void onGamePaused() {queue.add(new Paused());}
    public void onGameResumed() {queue.add(new Resumed());}
    public void onSetWidescreen(boolean enabled) {queue.add(new Widescreen(enabled));}
    public void onGameEnded(boolean win, double elapsedSeconds) {queue.add(new Ended(win, elapsedSeconds));}
    public void onPlaySpeech(int speechId, boolean showText, boolean introduction, int pathId) {queue.add(new Speech(speechId, showText, introduction, pathId));}
    public void onDoTransition(short pathId, Vector3f start) {queue.add(new Transition(pathId, Vec3.of(start)));}
    public void onFlashButton(TriggerAction.MakeType buttonType, short targetId, TriggerAction.ButtonType targetButtonType, boolean enabled, int time) {queue.add(new FlashButton(buttonType, targetId, targetButtonType, enabled, time));}
    public void onRotateViewAroundPoint(Vector3f point, boolean relative, int angle, int time) {queue.add(new Rotate(Vec3.of(point), relative, angle, time));}
    public void onShowMessage(int textId) {queue.add(new Message(textId));}
    public void onShowObjective(int objectiveId) {queue.add(new Objective(objectiveId));}
    public void onZoomViewToPoint(Vector3f point) {queue.add(new ZoomPoint(Vec3.of(point)));}
    public void onTileFlash(List<Point> points, boolean enabled, short keeperId) {queue.add(new FlashedTiles(points, enabled, keeperId));}
    public void onZoomViewToEntity(EntityId entityId) {queue.add(new ZoomEntity(entityId));}
    public void onShowUnitFlower(EntityId entityId, int interval) {queue.add(new Flower(entityId, interval));}
    public void onEntityAdded(short keeperId, ResearchableEntity research) {queue.add(new ResearchUpdate(ResearchAction.ADDED, keeperId, KeeperState.Research.capture(research)));}
    public void onEntityRemoved(short keeperId, ResearchableEntity research) {queue.add(new ResearchUpdate(ResearchAction.REMOVED, keeperId, KeeperState.Research.capture(research)));}
    public void onResearchStatusChanged(short keeperId, ResearchableEntity research) {queue.add(new ResearchUpdate(ResearchAction.CHANGED, keeperId, KeeperState.Research.capture(research)));}
    public void setPossession(EntityId target) {queue.add(new Possession(target));}
    public void onEnemySighted(EntityId entityId) {queue.add(new EnemySighted(entityId));}
}
