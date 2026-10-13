/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.trigger.actionpoint;

import com.jme3.util.SafeArrayList;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.IGameController;
import toniarts.openkeeper.game.controller.IGameTimer;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.data.ActionPoint;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;

/**
 *
 * @author ArchDemon
 */
public final class ActionPointTriggerLogicController implements IGameLogicUpdatable, toniarts.openkeeper.game.trigger.TriggerStateSource {

    private final SafeArrayList<ActionPointTriggerControl> triggers = new SafeArrayList<>(ActionPointTriggerControl.class);

    public ActionPointTriggerLogicController(final IGameController gameController, final ILevelInfo levelInfo, final IGameTimer gameTimer, final IMapController mapController,
            final ICreaturesController creaturesController, final IEntityPositionLookup entityPositionLookup) {

        // Create the triggers
        for (ActionPoint actionPoint : levelInfo.getActionPoints()) {
            if (actionPoint.getTriggerId() != 0) {
                triggers.add(new ActionPointTriggerControl(gameController, levelInfo, gameTimer, mapController, creaturesController, actionPoint.getTriggerId(), actionPoint, entityPositionLookup));
            }
        }
    }

    private ActionPointTriggerLogicController(java.util.List<ActionPointTriggerControl> controls) { triggers.addAll(controls); }
    public static ActionPointTriggerLogicController restoreState(IGameController game, ILevelInfo level, IGameTimer timer, IMapController map,
            ICreaturesController creatures, IEntityPositionLookup positions,
            toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State saved) {
        if (saved.kind() != toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.ACTION_POINT) throw new IllegalArgumentException("Wrong action-point registry");
        var points = new java.util.HashMap<Integer,ActionPoint>();
        for (var point : level.getActionPoints()) if (point.getTriggerId() != 0) points.put(point.getId(),point);
        if (!points.keySet().equals(saved.entries().stream().map(e -> e.ownerId()).collect(java.util.stream.Collectors.toSet()))) throw new IllegalArgumentException("Action-point owners differ");
        var controls = new java.util.ArrayList<ActionPointTriggerControl>();
        for (var entry : saved.entries()) {
            var point = points.get(entry.ownerId());
            if (point.getTriggerId() != entry.tree().triggerId()) throw new IllegalArgumentException("Action-point definition differs");
            var control = new ActionPointTriggerControl(game,level,timer,map,creatures,point.getTriggerId(),point,positions);
            control.restoreState(entry.tree());controls.add(control);
        }
        return new ActionPointTriggerLogicController(controls);
    }
    @Override public toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State captureRegistry() {
        return new toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State(toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.ACTION_POINT,
                triggers.stream().map(c -> toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Entry.actionPoint(c.captureState(),c.getActionPointId())).toList(), java.util.List.of());
    }

    @Override
    public java.util.List<toniarts.openkeeper.game.save.TriggerSnapshots.State> captureTrees() {
        return triggers.stream().map(control -> control.captureState()).toList();
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void processTick(float tpf) {
        for (ActionPointTriggerControl triggerControl : triggers.getArray()) {
            triggerControl.update(tpf);
        }
    }
}
