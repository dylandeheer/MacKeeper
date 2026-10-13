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
package toniarts.openkeeper.game.trigger.party;

import com.jme3.util.SafeArrayList;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.IGameController;
import toniarts.openkeeper.game.controller.IGameTimer;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.creature.IPartyController;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;

/**
 *
 * @author ArchDemon
 */
public final class PartyTriggerLogicController implements IGameLogicUpdatable, toniarts.openkeeper.game.trigger.TriggerStateSource {

    private final SafeArrayList<PartyTriggerControl> partyControllers = new SafeArrayList<>(PartyTriggerControl.class);

    public PartyTriggerLogicController(final IGameController gameController, final ILevelInfo levelInfo, final IGameTimer gameTimer, final IMapController mapController,
            final ICreaturesController creaturesController) {
        for (IPartyController partyController : creaturesController.getParties()) {
            if (partyController.getTriggerId() != 0) {
                partyControllers.add(new PartyTriggerControl(gameController, levelInfo, gameTimer, mapController, creaturesController, partyController.getTriggerId(), partyController));
            }
        }

        // TODO: get notified on new parties (a.k.a. parties re-spawned)
    }

    private PartyTriggerLogicController(java.util.List<PartyTriggerControl> controls) { partyControllers.addAll(controls); }
    public static PartyTriggerLogicController restoreState(IGameController game, ILevelInfo level, IGameTimer timer, IMapController map,
            ICreaturesController creatures,
            toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State saved) {
        if (saved.kind() != toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.PARTY) throw new IllegalArgumentException("Wrong party registry");
        var expected = new java.util.HashSet<Integer>();
        for (var party : creatures.getParties()) if (party.getTriggerId() != 0) expected.add((int)party.getPartyId());
        if (!expected.equals(saved.entries().stream().map(e -> e.ownerId()).collect(java.util.stream.Collectors.toSet()))) throw new IllegalArgumentException("Party trigger owners differ");
        var controls = new java.util.ArrayList<PartyTriggerControl>();
        for (var entry : saved.entries()) {
            var party = entry.partyInstance() == 0 ? creatures.getParty((short)entry.ownerId()) : creatures.getPartyById(entry.partyInstance());
            if (party == null || party.getPartyId() != entry.ownerId() || party.getId() != entry.partyInstance()
                    || party.getTriggerId() != entry.tree().triggerId()) throw new IllegalArgumentException("Party binding differs");
            var control = new PartyTriggerControl(game,level,timer,map,creatures,party.getTriggerId(),party);
            control.restoreState(entry.tree());controls.add(control);
        }
        return new PartyTriggerLogicController(controls);
    }
    @Override public toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State captureRegistry() {
        return new toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State(toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.PARTY,
                partyControllers.stream().map(PartyTriggerControl::captureBinding).toList(), java.util.List.of());
    }

    @Override
    public java.util.List<toniarts.openkeeper.game.save.TriggerSnapshots.State> captureTrees() {
        return partyControllers.stream().map(control -> control.captureState()).toList();
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void processTick(float tpf) {
        for (PartyTriggerControl partyTriggerControl : partyControllers.getArray()) {
            partyTriggerControl.update(tpf);
        }
    }
}
