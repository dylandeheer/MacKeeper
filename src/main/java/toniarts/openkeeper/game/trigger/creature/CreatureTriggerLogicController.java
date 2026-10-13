/*
 * Copyright (C) 2014-2016 OpenKeeper
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
package toniarts.openkeeper.game.trigger.creature;

import com.simsilica.es.EntityData;
import java.util.HashMap;
import java.util.Map;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.Trigger;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.IGameController;
import toniarts.openkeeper.game.controller.IGameTimer;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.trigger.AbstractThingTriggerControl;
import toniarts.openkeeper.game.trigger.AbstractThingTriggerLogicController;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Thing;

/**
 * A state for handling creature triggers
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class CreatureTriggerLogicController extends AbstractThingTriggerLogicController<ICreatureController> {

    public CreatureTriggerLogicController(final IGameController gameController, final ILevelInfo levelInfo, final IGameTimer gameTimer, final IMapController mapController,
            final ICreaturesController creaturesController, final PlayerService playerService, final EntityData entityData) {
        this(gameController, levelInfo, gameTimer, mapController, creaturesController, playerService, entityData, null);
    }

    public static CreatureTriggerLogicController restoreState(IGameController gameController, ILevelInfo levelInfo, IGameTimer gameTimer,
            IMapController mapController, ICreaturesController creaturesController, PlayerService playerService,
            toniarts.openkeeper.game.save.GameEntityData entityData, toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State saved) {
        java.util.Objects.requireNonNull(saved);
        return new CreatureTriggerLogicController(gameController, levelInfo, gameTimer, mapController, creaturesController, playerService, entityData, saved);
    }

    private CreatureTriggerLogicController(final IGameController gameController, final ILevelInfo levelInfo, final IGameTimer gameTimer, final IMapController mapController,
            final ICreaturesController creaturesController, final PlayerService playerService, final EntityData entityData, toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State saved) {
        super(initTriggers(levelInfo.getLevelData(), gameController, levelInfo, gameTimer, mapController,
                creaturesController, playerService),
                entityData, CreatureComponent.class, creaturesController, toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.CREATURE, saved,
                entry -> {
                    var creature = ((toniarts.openkeeper.game.controller.CreaturesController) creaturesController).getBoundController(entry.entity());
                    if (creature.getCreature().getCreatureId() != entry.definition()) throw new IllegalArgumentException("Creature trigger definition differs");
                    return creature;
                });
    }

    private static Map<Integer, AbstractThingTriggerControl<ICreatureController>> initTriggers(IKwdFile kwdFile, final IGameController gameController, final ILevelInfo levelInfo, final IGameTimer gameTimer, final IMapController mapController,
            final ICreaturesController creaturesController, final PlayerService playerService) {
        Map<Integer, AbstractThingTriggerControl<ICreatureController>> creatureTriggers = new HashMap<>();
        for (Thing.GoodCreature creature : kwdFile.getThings(Thing.GoodCreature.class)) {
            if (creature.getTriggerId() != 0) {
                creatureTriggers.put(creature.getTriggerId(), new CreatureTriggerControl(gameController, levelInfo, gameTimer, mapController,
                        creaturesController, creature.getTriggerId(), Player.GOOD_PLAYER_ID, playerService));
            }
        }
        for (Thing.KeeperCreature creature : kwdFile.getThings(Thing.KeeperCreature.class)) {
            if (creature.getTriggerId() != 0) {
                creatureTriggers.put(creature.getTriggerId(), new CreatureTriggerControl(gameController, levelInfo, gameTimer, mapController,
                        creaturesController, creature.getTriggerId(), creature.getPlayerId(), playerService));
            }
        }
        for (Thing.NeutralCreature creature : kwdFile.getThings(Thing.NeutralCreature.class)) {
            if (creature.getTriggerId() != 0) {
                creatureTriggers.put(creature.getTriggerId(), new CreatureTriggerControl(gameController, levelInfo, gameTimer, mapController,
                        creaturesController, creature.getTriggerId(), Player.NEUTRAL_PLAYER_ID, playerService));
            }
        }
        for (Thing.HeroParty heroParty : kwdFile.getThings(Thing.HeroParty.class)) {
            for (Thing.GoodCreature creature : heroParty.getHeroPartyMembers()) {
                if (creature.getTriggerId() != 0) {
                    creatureTriggers.put(creature.getTriggerId(), new CreatureTriggerControl(gameController, levelInfo, gameTimer, mapController,
                            creaturesController, creature.getTriggerId(), Player.GOOD_PLAYER_ID, playerService));
                }
            }
        }
        return creatureTriggers;
    }

}
