/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.trigger;

import java.util.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.save.TriggerRegistrySnapshots.*;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.trigger.actionpoint.ActionPointTriggerLogicController;
import toniarts.openkeeper.game.trigger.creature.CreatureTriggerLogicController;
import toniarts.openkeeper.game.trigger.door.DoorTriggerLogicController;
import toniarts.openkeeper.game.trigger.object.ObjectTriggerLogicController;
import toniarts.openkeeper.game.trigger.party.PartyTriggerLogicController;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerLogicController;

/** Staged registries. Dependencies must already be restored; activation is a separate step. */
public final class TriggerRegistries implements IGameLogicUpdatable, AutoCloseable {
    private final TriggerControl levelControl;
    private final List<IGameLogicUpdatable> controls;
    private TriggerRegistries(TriggerControl level, List<IGameLogicUpdatable> controls) {levelControl=level;this.controls=List.copyOf(controls);}
    public static TriggerRegistries restore(Map<Kind,State> saved, IGameController game, ILevelInfo level, IGameTimer timer,
            IMapController map, ICreaturesController creatures, IObjectsController objects, IDoorsController doors,
            IEntityPositionLookup positions, PlayerService players, GameEntityData data) {
        if (!saved.keySet().equals(EnumSet.allOf(Kind.class))) throw new IllegalArgumentException("Incomplete trigger registries");
        saved.forEach((kind,state)->{if(state.kind()!=kind)throw new IllegalArgumentException("Mismatched trigger registry kind");});
        int root=level.getLevelData().getGameLevel().getTriggerId();var levelState=saved.get(Kind.LEVEL);
        if (levelState.entries().size() != (root==0?0:1)) throw new IllegalArgumentException("Level trigger differs");
        TriggerControl levelControl=null;
        if(root!=0) {
            if(levelState.entries().getFirst().tree().triggerId()!=root)throw new IllegalArgumentException("Level trigger root differs");
            levelControl=new TriggerControl(game,level,timer,map,creatures,root);levelControl.restoreState(levelState.entries().getFirst().tree());
        }
        var controls=new ArrayList<IGameLogicUpdatable>();
        try {
            controls.add(PartyTriggerLogicController.restoreState(game,level,timer,map,creatures,saved.get(Kind.PARTY)));
            controls.add(CreatureTriggerLogicController.restoreState(game,level,timer,map,creatures,players,data,saved.get(Kind.CREATURE)));
            controls.add(ObjectTriggerLogicController.restoreState(game,level,timer,map,creatures,players,data,objects,saved.get(Kind.OBJECT)));
            controls.add(DoorTriggerLogicController.restoreState(game,level,timer,map,creatures,players,data,doors,objects,saved.get(Kind.DOOR)));
            controls.add(ActionPointTriggerLogicController.restoreState(game,level,timer,map,creatures,positions,saved.get(Kind.ACTION_POINT)));
            controls.add(PlayerTriggerLogicController.restoreState(game,level,timer,map,creatures,players,saved.get(Kind.PLAYER)));
            return new TriggerRegistries(levelControl,controls);
        } catch(RuntimeException|Error failure) {for(var control:controls.reversed())control.stop();throw failure;}
    }
    public Map<Kind,State> captureState() {
        var result=new EnumMap<Kind,State>(Kind.class);
        result.put(Kind.LEVEL,new State(Kind.LEVEL,levelControl==null?List.of():List.of(Entry.level(levelControl.captureState())),List.of()));
        for(var control:controls){var state=((TriggerStateSource)control).captureRegistry();result.put(state.kind(),state);}return Map.copyOf(result);
    }
    public Map<String,List<TriggerSnapshots.State>> captureTrees() {
        var result = new TreeMap<String,List<TriggerSnapshots.State>>();
        result.put("level", levelControl == null ? List.of() : List.of(levelControl.captureState()));
        for (var control : controls) result.put(control.getClass().getSimpleName(), ((TriggerStateSource)control).captureTrees());
        return Map.copyOf(result);
    }
    @Override public void processTick(float tpf) {if(levelControl!=null)levelControl.update(tpf);for(var control:controls)control.processTick(tpf);}
    @Override public void start() {for(var control:controls)control.start();}
    @Override public void stop() {for(var control:controls.reversed())control.stop();}
    @Override public void close() {stop();}
}
