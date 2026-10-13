/*
 * Copyright (C) 2014-2018 OpenKeeper
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
package toniarts.openkeeper.game.controller.creature;

import toniarts.openkeeper.tools.convert.map.Terrain;
import toniarts.openkeeper.game.controller.room.FiveByFiveRotatedController;
import toniarts.openkeeper.game.component.ObjectComponent;
import toniarts.openkeeper.game.task.TaskType;

import toniarts.openkeeper.game.controller.GameWorldController;

import toniarts.openkeeper.game.component.CreatureSalary;

import toniarts.openkeeper.game.logic.PaydaySystem;

import com.badlogic.gdx.ai.fsm.DefaultStateMachine;
import com.badlogic.gdx.ai.fsm.StateMachine;
import toniarts.openkeeper.game.data.CreatureControlState;
import com.badlogic.gdx.ai.pfa.GraphPath;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.component.Attack;
import toniarts.openkeeper.game.component.AttackTarget;
import toniarts.openkeeper.game.component.CreatureAi;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.CreatureExperience;
import toniarts.openkeeper.game.component.CreatureFall;
import toniarts.openkeeper.game.component.CreatureHunger;
import toniarts.openkeeper.game.component.CreatureImprisoned;
import toniarts.openkeeper.game.component.CreatureMeleeAttack;
import toniarts.openkeeper.game.component.CreatureRecuperating;
import toniarts.openkeeper.game.component.CreatureSleep;
import toniarts.openkeeper.game.component.CreatureSpell;
import toniarts.openkeeper.game.component.CreatureSpells;
import toniarts.openkeeper.game.component.CreatureTortured;
import toniarts.openkeeper.game.component.Fearless;
import toniarts.openkeeper.game.component.FollowTarget;
import toniarts.openkeeper.game.component.Gold;
import toniarts.openkeeper.game.component.Health;
import toniarts.openkeeper.game.component.InHand;
import toniarts.openkeeper.game.component.Mana;
import toniarts.openkeeper.game.component.Mobile;
import toniarts.openkeeper.game.component.Navigation;
import toniarts.openkeeper.game.component.Objective;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Party;
import toniarts.openkeeper.game.component.PlayerObjective;
import toniarts.openkeeper.game.component.PortalGem;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.component.Possessed;
import toniarts.openkeeper.game.component.Slapped;
import toniarts.openkeeper.game.component.Stored;
import toniarts.openkeeper.game.component.TaskComponent;
import toniarts.openkeeper.game.component.Threat;
import toniarts.openkeeper.game.component.Unconscious;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.IGameTimer;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.IObjectsController;
import toniarts.openkeeper.game.controller.IShotsController;
import toniarts.openkeeper.game.controller.entity.EntityController;
import toniarts.openkeeper.game.controller.entity.IEntityController;
import toniarts.openkeeper.game.controller.object.IObjectController;
import toniarts.openkeeper.game.controller.room.AbstractRoomController;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.data.ObjectiveType;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.navigation.steering.SteeringUtils;
import toniarts.openkeeper.game.task.ITaskManager;
import toniarts.openkeeper.game.task.Task;
import toniarts.openkeeper.tools.convert.map.ArtResource;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.CreatureSpell.CreatureSpellFlag;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.utils.WorldUtils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controls an entity with {@link CreatureAi} component. Basically supports the
 * AI state machine.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class CreatureController extends EntityController implements ICreatureController {

    private static final Logger logger = System.getLogger(CreatureController.class.getName());

    private final INavigationService navigationService;
    private final ITaskManager taskManager;
    private final IGameTimer gameTimer;
    private final Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings;
    private final ICreaturesController creaturesController;
    private final IEntityPositionLookup entityPositionLookup;
    private final ILevelInfo levelInfo;
    private final IShotsController shotsController;
    // TODO: All the data is not supposed to be on entities as they become too big, but I don't want these here either
    private final Creature creature;
    private final StateMachine<ICreatureController, CreatureState> stateMachine;
    private float motionless = 0;

    public CreatureControlState captureState(toniarts.openkeeper.game.save.TaskNodeReferences.Capture taskNodes) {
        return new CreatureControlState(entityId, creature.getId(), stateMachine.getCurrentState(),
                stateMachine.getPreviousState(), stateMachine.getGlobalState(), motionless, nextPayCheck,
                failedNavigationTask == null ? null : taskNodes.reference(failedNavigationTask), failedNavigationStart,
                failedNavigationTarget, failedNavigationTime, failedHeartPaths.entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .map(e -> new CreatureControlState.HeartPath(e.getKey(), e.getValue().start(), e.getValue().time())).toList());
    }

    /** Bind existing entities and task identities without entering/exiting states or assigning work. */
    public static CreatureController restoreState(CreatureControlState saved,
            toniarts.openkeeper.game.save.TaskNodeReferences.Bindings taskNodes,
            toniarts.openkeeper.game.save.GameEntityData data, Creature definition, INavigationService navigation,
            ITaskManager tasks, IGameTimer timer, Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings,
            ICreaturesController creatures, IEntityPositionLookup positions, IMapController map,
            ILevelInfo level, IObjectsController objects, IShotsController shots) {
        if (!data.wasAllocated(saved.entityId()) || definition == null || definition.getId() != saved.creatureType())
            throw new IllegalArgumentException("Missing creature identity/definition");
        for (var path : saved.heartPaths()) if (!data.wasAllocated(path.roomId()))
            throw new IllegalArgumentException("Unallocated failed heart path reference");
        Task failedTask = saved.failedTask() == null ? null : taskNodes.resolve(saved.failedTask());
        return new CreatureController(saved.entityId(), data, definition, navigation, tasks, timer, settings,
                creatures, positions, map, level, objects, shots, saved, failedTask);
    }

    public CreatureController(EntityId entityId, EntityData entityData, Creature creature, INavigationService navigationService,
            ITaskManager taskManager, IGameTimer gameTimer, Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings,
            ICreaturesController creaturesController, IEntityPositionLookup entityPositionLookup, IMapController mapController,
            ILevelInfo levelInfo, IObjectsController objectsController, IShotsController shotsController) {
        this(entityId, entityData, creature, navigationService, taskManager, gameTimer, gameSettings,
                creaturesController, entityPositionLookup, mapController, levelInfo, objectsController, shotsController, null, null);
    }

    private CreatureController(EntityId entityId, EntityData entityData, Creature creature, INavigationService navigationService,
            ITaskManager taskManager, IGameTimer gameTimer, Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings,
            ICreaturesController creaturesController, IEntityPositionLookup entityPositionLookup, IMapController mapController,
            ILevelInfo levelInfo, IObjectsController objectsController, IShotsController shotsController,
            CreatureControlState saved, Task failedTask) {
        super(entityId, entityData, objectsController, mapController);
        this.navigationService = navigationService;
        this.taskManager = taskManager;
        this.creature = creature;
        this.gameTimer = gameTimer;
        this.gameSettings = gameSettings;
        this.creaturesController = creaturesController;
        this.entityPositionLookup = entityPositionLookup;
        this.levelInfo = levelInfo;
        this.shotsController = shotsController;
        this.stateMachine = saved == null ? new DefaultStateMachine<>(this)
                : new toniarts.openkeeper.game.controller.state.RestoredStateMachine<>(this, saved.current(), saved.previous(), saved.global());
        if (saved != null) {
            motionless = saved.motionless();nextPayCheck = saved.nextPayCheck();failedNavigationTask = failedTask;
            failedNavigationStart = saved.failedStart();failedNavigationTarget = saved.failedTarget();failedNavigationTime = saved.failedTime();
            for (var path : saved.heartPaths()) failedHeartPaths.put(path.roomId(), new FailedHeartPath(path.start(), path.time()));
        }
    }

    @Override
    public boolean shouldFleeOrAttack() {
        checkSurroundings();

        // Check fleeing, TODO: Always flee?
        boolean inDHeart = false;
        if (entityData.getComponent(entityId, Fearless.class) == null) {
            Threat threatComponent = entityData.getComponent(entityId, Threat.class);
            int threat = threatComponent != null ? threatComponent.threat : 0;
            int fear = entityData.getComponent(entityId, CreatureComponent.class).fear;
            int threatToUs = getEnemyThreat();
            int threatCaused = creature.getFlags().contains(Creature.CreatureFlag.ALWAYS_FLEE)
                    || isHealthAtCriticalLevel() ? threat : getOurThreat();
            if (threatToUs - threatCaused > fear && (getFellowFighters() == 0
                    || creature.getFlags().contains(Creature.CreatureFlag.ALWAYS_FLEE))) {

                // No longer flee from DHeart
                IRoomController roomController = mapController.getRoomControllerByCoordinates(getCreatureCoordinates());
                if (roomController == null || !roomController.isDungeonHeart()) {
                    if (!stateMachine.isInState(CreatureState.FLEE)) {

                        stateMachine.changeState(CreatureState.FLEE);
                    }
                    return true;
                } else {
                    inDHeart = true;
                }
            }
        }

        // Should we attack, try to avoid i.e. imps engaging in a fight
        if ((creature.getFightStyle()
                != Creature.FightStyle.NON_FIGHTER || inDHeart) && getAttackTarget() != null) {
            if (!stateMachine.isInState(CreatureState.FIGHT)) {
                stateMachine.changeState(CreatureState.FIGHT);
            }
            return true;
        }

        return false;
    }

    @Override
    public void checkSurroundings() {

        // Scan for neutral creatures to claim
        short ownerId = getOwnerId();
        if (ownerId == Player.NEUTRAL_PLAYER_ID || ownerId == Player.GOOD_PLAYER_ID) {
            return;
        }

        for (EntityId entity : entityPositionLookup.getSensedEntities(entityId)) {
            Owner owner = entityData.getComponent(entity, Owner.class);
            if (owner != null && owner.ownerId == Player.NEUTRAL_PLAYER_ID) {
                convertCreature(entity, ownerId);
            }
        }
    }

    /**
     * Checks if we should fear death
     *
     * @return true if we have critically low health level
     */
    private boolean isHealthAtCriticalLevel() {
        return gameSettings.get(Variable.MiscVariable.MiscType.CREATURE_CRITICAL_HEALTH_PERCENTAGE_OF_MAX).getValue() > getHealthPercentage();
    }

    private int getFellowFighters() {
        int fellowFighters = 0;
        for (EntityId entity : entityPositionLookup.getSensedEntities(entityId)) {
            if (isAlly(entity) && !isIncapacitated(entity) && getEntityFightingStyle(entity) != Creature.FightStyle.NON_FIGHTER) {
                fellowFighters++;
            }
        }

        return fellowFighters;
    }

    /**
     * Gets the total threat caused by the enemies visible
     *
     * @return total enemy threat
     */
    private int getEnemyThreat() {
        int enemyThreat = 0;
        for (EntityId entity : entityPositionLookup.getSensedEntities(entityId)) {
            if (isEnemy(entity) && isThreat(entity)) {
                enemyThreat += getThreat(entity);
            }
        }

        return enemyThreat;
    }

    /**
     * Checks if the given entity posseses a threat to anyone at all
     *
     * @param entity the entity to check
     * @return is the entity a threat to anyone
     */
    private boolean isThreat(EntityId entity) {
        return !isIncapacitated(entity) && !isCaptive(entity);
    }

    /**
     * Checks if the given entity is a captive. Unable to join a fight and also
     * posses no threat to anyone
     *
     * @param entity the entity
     * @return is entity a captive
     */
    private boolean isCaptive(EntityId entity) {
        // TODO: Fighting in arena is kind of captivity
        return isImprisoned(entityData, entity) || isTortured(entityData, entity);
    }

    /**
     * Gets the total threat caused by us. Meaning the band of brothers visible
     * to us
     *
     * @return total threat caused by us
     */
    private int getOurThreat() {
        int ourThreat = 0;
        for (EntityId entity : entityPositionLookup.getSensedEntities(entityId)) {
            if (isAlly(entity) && isThreat(entity)) {
                ourThreat += getThreat(entity);
            }
        }

        return ourThreat;
    }

    @Override
    public void unassingCurrentTask() {
        Task assignedTask = getAssignedTask();
        if (assignedTask != null) {
            assignedTask.unassign(this);
            entityData.removeComponent(entityId, TaskComponent.class);
        }
    }

    @Override
    public void navigateToRandomPoint() {
        final Position position = entityData.getComponent(entityId, Position.class);
        final Mobile mobile = entityData.getComponent(entityId, Mobile.class);
        final Owner owner = entityData.getComponent(entityId, Owner.class);
        if (position != null && mobile != null && owner != null) {
            Point start = WorldUtils.vectorToPoint(position.position);
            Point destination = navigationService.findRandomAccessibleTile(start, 10, this);
            if (destination != null) {
                createNavigation(start, destination, null);
            }
        }
    }

    @Override
    public IPartyController getParty() {
        Party party = entityData.getComponent(entityId, Party.class);
        if (party != null) {
            return creaturesController.getPartyById(party.partyId);
        }
        return null;
    }

    @Override
    public StateMachine<ICreatureController, CreatureState> getStateMachine() {
        return stateMachine;
    }

    @Override
    public boolean hasObjective() {
        Objective creatureObjective = entityData.getComponent(entityId, Objective.class);
        return (creatureObjective != null && creatureObjective.objective != null);
    }

    @Override
    public boolean followObjective() {
        return taskManager.assignObjectiveTask(this, entityData.getComponent(entityId, Objective.class).objective);
    }

    @Override
    public boolean needsLair() {
        return getOwnerId() >= Player.KEEPER1_ID && entityData.getComponent(entityId, CreatureSleep.class) != null;
    }

    @Override
    public boolean hasLair() {
        CreatureSleep creatureSleep = entityData.getComponent(entityId, CreatureSleep.class);
        return creatureSleep != null && creatureSleep.lairObjectId != null && entityData.getComponent(creatureSleep.lairObjectId, Position.class) != null;
    }

    @Override
    public boolean findLair() {
        return taskManager.assignClosestRoomTask(this, AbstractRoomController.ObjectType.LAIR, null);
    }

    @Override
    public boolean isNeedForSleep() {
        CreatureSleep creatureSleep = entityData.getComponent(entityId, CreatureSleep.class);
        return creatureSleep != null && needsLair() && (gameTimer.getGameTime() - creatureSleep.lastSleepTime >= creature.getAttributes().getTimeAwake()
                || isNeedForRecuperating());
    }

    private boolean isNeedForRecuperating() {
        return gameSettings.get(Variable.MiscVariable.MiscType.CREATURE_SLEEPS_WHEN_BELOW_PERCENT_HEALTH).getValue() >= getHealthPercentage();
    }

    @Override
    public boolean goToSleep() {
        return taskManager.assignSleepTask(this);
    }

    @Override
    public void findWork(WorkContinuation continuation) {

        // See if we have some available work
        if (isWorker()) {
            taskManager.addToUnemployedWorkerQueue(this, continuation);
            return;
        }

        short owner = getOwnerId();
        if (owner == Player.NEUTRAL_PLAYER_ID || owner == Player.GOOD_PLAYER_ID) {

            // Currently no creature specific jobs offered for neutral or good players
            continuation.complete(this, false);
            return;
        }

        // See that is there a prefered job for us
        // FIXME: moods
        List<Creature.JobPreference> jobs = new ArrayList<>();
        if (creature.getHappyJobs() != null) {
            for (Creature.JobPreference jobPreference : creature.getHappyJobs()) {
                if (taskManager.isTaskAvailable(this, jobPreference.getJobType())) {
                    jobs.add(jobPreference);
                }
            }
        }

        // Choose
        if (!jobs.isEmpty()) {
            continuation.complete(this, taskManager.assignTask(this, chooseOnWeight(jobs).getJobType()));
            return;
        }

        continuation.complete(this, false);
    }

    private Creature.JobPreference chooseOnWeight(List<Creature.JobPreference> items) {
        double completeWeight = 0.0;
        for (Creature.JobPreference item : items) {
            completeWeight += item.getChance();
        }
        double r = creaturesController.getRandom().nextDouble() * completeWeight;
        double countWeight = 0.0;
        for (Creature.JobPreference item : items) {
            countWeight += item.getChance();
            if (countWeight >= r) {
                return item;
            }
        }
        return null;
    }

    @Override
    public boolean isWorker() {
        return entityData.getComponent(entityId, CreatureComponent.class).worker;
    }

    @Override
    public void executeAssignedTask() {
        TaskComponent taskComponent = entityData.getComponent(entityId, TaskComponent.class);
        if (!taskComponent.taskStarted) {
            entityData.setComponent(entityId, new TaskComponent(taskComponent.taskId, taskComponent.targetEntity, taskComponent.targetLocation, taskComponent.taskType, taskComponent.taskDuration, true));
        }
        if (isAssignedTaskValid()) {
            getAssignedTask().executeTask(this, taskComponent.taskDuration);
        }
    }

    @Override
    public boolean isTooMuchGold() {
        return getGold() >= getMaxGold() && isWorker();
    }

    private double nextPayCheck;

    @Override
    public boolean collectPay() {
        if (gameTimer.getGameTime() < nextPayCheck || !PaydaySystem.eligible(entityData, entityId)) return false;
        Task current = getAssignedTask();
        if (current != null && current.getTaskType() == TaskType.COLLECT_PAY) return false;
        var delay = gameSettings.get(Variable.MiscVariable.MiscType.DELAY_BEFORE_PAY_REEVALUATION_SECONDS);
        nextPayCheck = gameTimer.getGameTime() + (delay == null ? 4 : Math.max(0.1, delay.getValue()));
        var salary = entityData.getComponent(entityId, CreatureSalary.class);
        if (salary != null && salary.employer == getOwnerId() && salary.owed > 0 && taskManager.assignPaydayTask(this)) {
            navigateToAssignedTask();
            return true;
        }
        return false;
    }

    @Override
    public boolean dropGoldToTreasury() {
        if (getGold() > 0 && getOwnerId() >= Player.KEEPER1_ID && isWorker()) {
            if (taskManager.assignGoldToTreasuryTask(this)) {
                navigateToAssignedTask();
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isStopped() {
        return entityData.getComponent(entityId, Navigation.class) == null;
    }

    private static final double FAILED_TASK_PATH_RETRY_SECONDS = 1;
    private Task failedNavigationTask;
    private Point failedNavigationStart;
    private Point failedNavigationTarget;
    private double failedNavigationTime;

    @Override
    public void navigateToAssignedTask() {
        Task assignedTask = getAssignedTask();
        Vector2f loc = assignedTask == null ? null : assignedTask.getTarget(this);
        if (loc == null || isNear(loc)) {
            failedNavigationTask = null;
            return;
        }
        Point start = getCreatureCoordinates();
        Point destination = WorldUtils.vectorToPoint(loc);
        double now = gameTimer.getGameTime();
        // WORK retries whenever stopped. A blocked task must not run a full-map search
        // every tick. Changed tasks/positions/targets retry immediately; an unchanged
        // route retries within one game second so opened doors and dug paths recover.
        if (assignedTask == failedNavigationTask && start.equals(failedNavigationStart)
                && destination.equals(failedNavigationTarget) && now >= failedNavigationTime
                && now < failedNavigationTime + FAILED_TASK_PATH_RETRY_SECONDS) return;
        if (createNavigation(start, destination, assignedTask.isFaceTarget() ? assignedTask.getTaskLocation() : null)) {
            failedNavigationTask = assignedTask;
            failedNavigationStart = start;
            failedNavigationTarget = destination;
            failedNavigationTime = now;
        } else {
            failedNavigationTask = null;
        }
    }

    private boolean createNavigation(Point currentLocation, Point destination, Point faceTarget) {
        GraphPath<IMapTileInformation> path = navigationService.findPath(currentLocation, destination, this);
        if (path == null) {
            logger.log(Level.WARNING, "No path from {0} to {1}", getCreatureCoordinates(), destination);
            return true;
        }
        entityData.setComponent(entityId, new Navigation(destination, faceTarget, SteeringUtils.pathToList(path)));
        return false;
    }

    @Override
    public boolean isAtAssignedTaskTarget() {
        Task assignedTask = getAssignedTask();
        return (assignedTask != null && assignedTask.getTarget(this) != null
                //&& !workNavigationRequired
                && isStopped()
                && isNear(assignedTask.getTarget(this)));
    }

    private boolean isNear(Vector2f target) {
        Vector3f currentPos = getPosition();
        return (target.distanceSquared(currentPos.x, currentPos.z) < 0.5f);
    }

    @Override
    public void dropGold() {
        synchronized (GameWorldController.GOLD_LOCK) {
            Gold carried = entityData.getComponent(entityId, Gold.class);
            if (carried == null || carried.gold <= 0) {
                return;
            }
            int maxPile = (int) gameSettings.get(Variable.MiscVariable.MiscType.MAX_GOLD_PILE_OUTSIDE_TREASURY).getValue();
            if (maxPile <= 0) {
                throw new IllegalStateException("Loose gold pile capacity must be positive");
            }
            Point coordinates = getCreatureCoordinates();
            short owner = getOwnerId();
            int remaining = carried.gold;
            while (remaining > 0) {
                int amount = Math.min(remaining, maxPile);
                objectsController.addLooseGold(owner, coordinates.x, coordinates.y, amount, maxPile);
                remaining -= amount;
                // Debit only completed piles, so a failed spawn leaves the rest in the purse.
                entityData.setComponent(entityId, new Gold(remaining, carried.maxGold));
            }
        }
    }

    @Override
    public boolean isWorkNavigationRequired() {
        // TODO:
        return false;
    }

    @Override
    public boolean isAssignedTaskValid() {
        Task assignedTask = getAssignedTask();
        return (assignedTask != null && assignedTask.isValid(this));
    }

    @Override
    public IEntityController getAttackTarget() {
        AttackTarget previous = entityData.getComponent(entityId, AttackTarget.class);
        if (previous != null && isAttackableCreature(previous.entityId)) {
            return creaturesController.createController(previous.entityId);
        }

        // Defenders take precedence over a heart, including while already attacking it.
        EntityId nearestEnemy = null;
        float nearestDistance = Float.MAX_VALUE;
        var sensed = entityPositionLookup.getSensedEntities(entityId);
        for (EntityId target : sensed) {
            if (isAttackableCreature(target) && (isWithinAttackDistance(target) || hasPathToEntity(target))) {
                float distance = getDistanceToCreature(target);
                if (nearestEnemy == null || distance < nearestDistance || distance == nearestDistance && target.compareTo(nearestEnemy) < 0) {
                    nearestDistance = distance;
                    nearestEnemy = target;
                }
            }
        }
        if (nearestEnemy != null) {
            setAttackTarget(nearestEnemy);
            return creaturesController.createController(nearestEnemy);
        }
        if (previous != null && heartRoom(previous.entityId) != null && isEnemy(previous.entityId)
                && (isWithinAttackDistance(previous.entityId) || heartApproach(heartRoom(previous.entityId)) != null)) {
            return entityPositionLookup.getEntityController(previous.entityId);
        }
        for (EntityId target : sensed) {
            IRoomController heart = heartRoom(target);
            if (heart != null && isEnemy(target)
                    && (isWithinAttackDistance(target) || heartApproach(heart) != null)) {
                float distance = getDistanceToCreature(target);
                if (nearestEnemy == null || distance < nearestDistance || distance == nearestDistance && target.compareTo(nearestEnemy) < 0) {
                    nearestDistance = distance;
                    nearestEnemy = target;
                }
            }
        }
        setAttackTarget(nearestEnemy);
        return nearestEnemy == null ? null : entityPositionLookup.getEntityController(nearestEnemy);
    }

    private boolean isAttackableCreature(EntityId target) {
        Health health = entityData.getComponent(target, Health.class);
        return health != null && health.health > 0 && creaturesController.isValidEntity(target) && isEnemy(target)
                && entityData.getComponent(target, Position.class) != null
                && !(isIncapacitated(target) || isFleeing(target) || isCaptive(target));
    }

    private IRoomController heartRoom(EntityId target) {
        var object = entityData.getComponent(target, ObjectComponent.class);
        if (object == null || object.objectId != FiveByFiveRotatedController.OBJECT_HEART_ID) return null;
        Position position = entityData.getComponent(target, Position.class);
        Health health = entityData.getComponent(target, Health.class);
        if (position == null || health == null || health.health <= 0) return null;
        IRoomController room = mapController.getRoomControllerByCoordinates(WorldUtils.vectorToPoint(position.position));
        return room != null && room.isDungeonHeart() && !room.isDestroyed()
                && room.getFloorFurniture().contains(target) ? room : null;
    }

    private record FailedHeartPath(Point start, double time) { }
    private final Map<EntityId, FailedHeartPath> failedHeartPaths = new java.util.HashMap<>();

    private Point heartApproach(IRoomController heart) {
        Point start = getCreatureCoordinates(), center = heart.getRoomInstance().getCenter();
        float range = getPreferredAttackDistance();
        Navigation current = entityData.getComponent(entityId, Navigation.class);
        if (current != null && center.equals(current.faceTarget)) {
            if (heart.getRoomInstance().getCoordinates().contains(current.target)
                    && heart.isTileAccessible(null, current.target)
                    && canAttackHeartFrom(current.target.x, current.target.y, range, center)
                    && remainingRouteIsAccessible(current)) return current.target;
            entityData.removeComponent(entityId, Navigation.class);
        }
        double now = gameTimer.getGameTime();
        FailedHeartPath failed = failedHeartPaths.get(heart.getEntityId());
        if (failed != null && failed.start.equals(start) && now >= failed.time && now - failed.time < 1) return null;
        // The central 3x3 is impassable. Short-range melee must avoid unreachable corner swings.
        Point destination = heart.getRoomInstance().getCoordinates().stream()
                .filter(p -> heart.isTileAccessible(null, p) && canAttackHeartFrom(p.x, p.y, range, center))
                .sorted(Comparator.<Point>comparingDouble(p -> p.distance(start)).thenComparingInt(p -> p.y).thenComparingInt(p -> p.x))
                .filter(p -> p.equals(start) || navigationService.findPath(start, p, this) != null)
                .findFirst().orElse(null);
        if (destination == null) failedHeartPaths.put(heart.getEntityId(), new FailedHeartPath(new Point(start.x, start.y), now));
        else failedHeartPaths.remove(heart.getEntityId());
        return destination;
    }

    private boolean remainingRouteIsAccessible(Navigation route) {
        Vector3f position = getPosition();
        int nearest = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < route.navigationPath.size(); i++) {
            var waypoint = route.navigationPath.get(i);
            float candidate = waypoint.dst2(position.x, position.z);
            if (candidate < distance) { nearest = i; distance = candidate; }
        }
        for (int i = nearest; i < route.navigationPath.size(); i++) {
            var waypoint = route.navigationPath.get(i);
            var tile = mapController.getMapData().getTile(WorldUtils.vectorToPoint(waypoint));
            if (tile == null || !navigationService.isAccessible(null, tile, this)) return false;
        }
        return true;
    }

    private boolean canAttackHeartFrom(float x, float z, float range, Point center) {
        // Measure to the central 3x3 footprint, not the below-floor model origin.
        float dx = Math.max(0, Math.abs(x - center.x) - 1.5f);
        float dz = Math.max(0, Math.abs(z - center.y) - 1.5f);
        return range >= 0 && dx * dx + dz * dz <= range * range
                && clearHeartAttackLine(WorldUtils.vectorToPoint(new Vector2f(x, z)), center);
    }

    private boolean clearHeartAttackLine(Point from, Point target) {
        int x = from.x, y = from.y, dx = target.x - x, dy = target.y - y;
        int nx = Math.abs(dx), ny = Math.abs(dy), sx = Integer.signum(dx), sy = Integer.signum(dy);
        int ix = 0, iy = 0;
        // Supercover tile ray: both neighbors must be clear at a diagonal corner.
        while (ix < nx || iy < ny) {
            long horizontal = (1L + 2L * ix) * ny, vertical = (1L + 2L * iy) * nx;
            if (horizontal == vertical) {
                if (blocksHeartAttack(x + sx, y) || blocksHeartAttack(x, y + sy)) return false;
                x += sx; y += sy; ix++; iy++;
            } else if (horizontal < vertical) { x += sx; ix++; }
            else { y += sy; iy++; }
            if (blocksHeartAttack(x, y)) return false;
        }
        return true;
    }

    private boolean blocksHeartAttack(int x, int y) {
        var tile = mapController.getMapData().getTile(x, y);
        return tile == null || mapController.getTerrain(tile).getFlags().contains(Terrain.TerrainFlag.SOLID)
                || entityPositionLookup.isTileBlocked(tile, getOwnerId());
    }

    private boolean hasPathToEntity(EntityId entity) {
        Point ourPos = WorldUtils.vectorToPoint(getPosition());
        Point theirPos = WorldUtils.vectorToPoint(getPosition(entityData, entity));

        return ourPos.equals(theirPos) || navigationService.findPath(ourPos, theirPos, this) != null;
    }

    private void setAttackTarget(EntityId entity) {
        if (entity == null) {
            entityData.removeComponent(entityId, AttackTarget.class);
        } else {
            entityData.setComponent(entityId, new AttackTarget(entity));
        }
    }

    @Override
    public boolean isWithinAttackDistance(EntityId attackTarget) {
        float distanceNeeded = Math.max(getPreferredAttackDistance(), getPossibleAttackDistance());

        // TODO: currently we move only with a tile precision, so accept attack if on the same tile already
        return isInRange(distanceNeeded, attackTarget);
    }

    /**
     * Gets currently possible maximum attack distance for this creature,
     * includes the cooldowns
     *
     * @return maximum distance can attack at the moment
     */
    private float getPossibleAttackDistance() {
        float distanceNeeded = entityData.getComponent(entityId, CreatureMeleeAttack.class).range;
        for (CreatureSpell attack : getCreatureSpells()) {
            toniarts.openkeeper.tools.convert.map.CreatureSpell spell = levelInfo.getLevelData().getCreatureSpellById(attack.creatureSpellId);
            if (spell.getFlags().contains(CreatureSpellFlag.IS_ATTACKING) && isAttackRecharged(attack)) {
                distanceNeeded = Math.max(distanceNeeded, attack.range);
            }
        }

        return distanceNeeded;
    }

    /**
     * Gets the preferred attack distance from target for this creature's
     * figting style
     *
     * @return preferred attack distance
     */
    private float getPreferredAttackDistance() {
        float distanceNeeded = entityData.getComponent(entityId, CreatureMeleeAttack.class).range; // The melee range, the shortest range
        if (creature.getFightStyle() == Creature.FightStyle.SUPPORT) {

            // Get max distance we can cast all spells, and hopefully stay safe
            Optional<Float> shortestDistance = getShortestAttackSpellDistanceNeeded();
            if (shortestDistance.isPresent()) {
                distanceNeeded = shortestDistance.get();
            }
        }

        return distanceNeeded;
    }

    private Optional<Float> getShortestAttackSpellDistanceNeeded() {
        Float shortestDistance = null;
        for (CreatureSpell attack : getCreatureSpells()) {
            toniarts.openkeeper.tools.convert.map.CreatureSpell spell = levelInfo.getLevelData().getCreatureSpellById(attack.creatureSpellId);
            if (spell.getFlags().contains(CreatureSpellFlag.IS_ATTACKING)) {
                if (shortestDistance == null) {
                    shortestDistance = attack.range;
                } else {
                    shortestDistance = Math.min(shortestDistance, attack.range);
                }
            }
        }

        return Optional.ofNullable(shortestDistance);
    }

    private boolean isInRange(float range, EntityId target) {
        IRoomController heart = heartRoom(target);
        if (heart != null) {
            Vector3f position = getPosition();
            return canAttackHeartFrom(position.x, position.z, range, heart.getRoomInstance().getCenter());
        }
        return range >= getDistanceToCreature(target) || (isStopped() && isAtSameTile(target));
    }

    private List<CreatureSpell> getCreatureSpells() {
        CreatureSpells creatureSpells = entityData.getComponent(entityId, CreatureSpells.class);
        if (creatureSpells == null) {
            return Collections.emptyList();
        }

        List<CreatureSpell> spells = new ArrayList<>(creatureSpells.creatureSpells.size());
        for (EntityId entity : creatureSpells.creatureSpells) {
            spells.add(entityData.getComponent(entity, CreatureSpell.class));
        }

        return spells;
    }

    private boolean isAtSameTile(EntityId attackTarget) {
        return getCreatureCoordinates().equals(getCreatureCoordinates(entityData, attackTarget));
    }

    @Override
    public void stop() {
        // Note that this is the updatable stop, not the creature stop...
    }

    @Override
    public void executeAttack(EntityId attackTarget) {
        // Targets can be captured, picked up or destroyed between selection and execution.
        if (!isAttackableCreature(attackTarget) && (heartRoom(attackTarget) == null || !isEnemy(attackTarget))) return;

        // TODO: how to apply the damage? Create a component for THIS creature that adds the damage to enemy after the countdown is finished?
        // Always prefer melee if in range
        CreatureMeleeAttack creatureMeleeAttack = entityData.getComponent(entityId, CreatureMeleeAttack.class);
        if (isInRange(creatureMeleeAttack.range, attackTarget)) {
            if (isAttackRecharged(creatureMeleeAttack)) {
                entityData.setComponent(entityId, new CreatureMeleeAttack(creatureMeleeAttack, gameTimer.getGameTime()));
                stateMachine.changeState(CreatureState.MELEE_ATTACK);

                // Set the damage
                EntityController.setDamage(entityData, attackTarget, creatureMeleeAttack.damage, getOwnerId());
            }
        } else {
            Optional<CreatureSpell> attack = getPreferredAttackSpell(attackTarget);
            if (attack.isPresent()) {
                CreatureSpells creatureSpells = entityData.getComponent(entityId, CreatureSpells.class);
                for (EntityId creatureSpellEntity : creatureSpells.creatureSpells) {
                    CreatureSpell spell = entityData.getComponent(creatureSpellEntity, CreatureSpell.class);
                    if (spell.creatureSpellId == attack.get().creatureSpellId) {
                        entityData.setComponent(creatureSpellEntity, new CreatureSpell(attack.get(), gameTimer.getGameTime()));
                        break;
                    }
                }
                stateMachine.changeState(CreatureState.CAST_SPELL);

                // TODO: What is the alternative shot all about?
                toniarts.openkeeper.tools.convert.map.CreatureSpell creatureSpell = levelInfo.getLevelData().getCreatureSpellById(attack.get().creatureSpellId);
                shotsController.createShot(creatureSpell.getShotTypeId(), creatureSpell.getShotData1(), creatureSpell.getShotData2(), getOwnerId(), getPosition(), attackTarget);

                // TODO: Of course not like that, the shot is created and it does what it does
                // Set the damage
                EntityController.setDamage(entityData, attackTarget, creatureSpell.getShotData1(), getOwnerId());
            }
        }
    }

    /**
     * Gets preferred attack creature spell that is available to cast to the
     * target
     *
     * @param attackTarget target to attack
     * @return creature spell to cast on the unsuspecting victim
     */
    private Optional<CreatureSpell> getPreferredAttackSpell(EntityId attackTarget) {
        List<CreatureSpell> creatureSpells = getCreatureSpells();
        if (creatureSpells.isEmpty()) {
            return Optional.empty();
        }

        // Sort in order of preference
        return creatureSpells
                .stream()
                .filter(creatureSpell -> isAttackRecharged(creatureSpell) && isInRange(creatureSpell.range, attackTarget) && levelInfo.getLevelData().getCreatureSpellById(creatureSpell.creatureSpellId).getFlags().contains(CreatureSpellFlag.IS_ATTACKING))
                .sorted(Comparator.<CreatureSpell>comparingInt(creatureSpell -> levelInfo.getLevelData().getCreatureSpellById(creatureSpell.creatureSpellId).getCombatPoints()).reversed())
                .findFirst();
    }

    private boolean isAttackRecharged(Attack attack) {
        return attack.attactStartTime == null || attack.attactStartTime + attack.rechargeTime <= gameTimer.getGameTime();
    }

    @Override
    public void navigateToAttackTarget(EntityId attackTarget) {
        IRoomController heart = heartRoom(attackTarget);
        if (heart != null) {
            if (!isEnemy(attackTarget)) return;
            Point destination = heartApproach(heart);
            Navigation current = entityData.getComponent(entityId, Navigation.class);
            if (destination != null && !destination.equals(getCreatureCoordinates())
                    && (current == null || !destination.equals(current.target))) {
                createNavigation(getCreatureCoordinates(), destination, heart.getRoomInstance().getCenter());
            }
            return;
        }
        Vector3f targetPosition = getPosition(entityData, attackTarget);
        if (targetPosition != null) {

            // Check if we are already going there
            Point destination = WorldUtils.vectorToPoint(targetPosition);
            Navigation currentNavigation = entityData.getComponent(entityId, Navigation.class);
            if (currentNavigation != null && currentNavigation.target.equals(destination)) {
                return;
            }

            // Just now simply go where the target currently is
            Point ourPosition = getCreatureCoordinates();
            if (destination != null && !destination.equals(ourPosition)) {
                createNavigation(ourPosition, destination, null);
            }
        }
    }

    @Override
    public ICreatureController getFollowTarget() {
        FollowTarget followTarget = entityData.getComponent(entityId, FollowTarget.class);
        if (followTarget != null && entityData.getComponent(followTarget.entityId, Position.class) != null) {
            return creaturesController.createController(followTarget.entityId);
        }
        return null;
    }

    @Override
    public boolean shouldNavigateToFollowTarget() {
        return (isStopped() && getDistanceToCreature(getFollowTarget().getEntityId()) > 1.5f) || (getDistanceToCreature(getFollowTarget().getEntityId()) > 2.5f);
    }

    @Override
    public Task getAssignedTask() {
        TaskComponent taskComponent = entityData.getComponent(entityId, TaskComponent.class);
        if (taskComponent != null) {
            return taskManager.getTaskById(taskComponent.taskId);
        }
        return null;
    }

    @Override
    public float getDistanceToCreature(EntityId target) {

        // FIXME: now just direct distance, should be perhaps real distance that the creature needs to traverse to reach the target
        Position targetPosition = entityData.getComponent(target, Position.class);
        Vector3f ourPosition = getPosition();
        if (targetPosition == null || ourPosition == null) {
            return Float.MAX_VALUE;
        }
        return ourPosition.distance(targetPosition.position);
    }

    @Override
    public void navigateToRandomPointAroundTarget(EntityId target, int radius) {
        Position targetPosition = entityData.getComponent(target, Position.class);
        if (targetPosition != null) {

            // To keep up with the target, see if it has a target it is navigating to
            Point destination;
            Navigation targetNavigation = entityData.getComponent(target, Navigation.class);
            if (targetNavigation != null) {
                destination = targetNavigation.target;
            } else {
                destination = WorldUtils.vectorToPoint(targetPosition.position);
            }

            Point p = navigationService.findRandomAccessibleTile(destination, radius, this);
            Point ourPosition = getCreatureCoordinates();
            if (p != null && p != ourPosition) {
                createNavigation(ourPosition, p, null);
            }
        }
    }

    @Override
    public void setFollowTarget(EntityId target) {
        entityData.setComponent(entityId, new FollowTarget(target));
    }

    @Override
    public void resetFollowTarget() {
        entityData.removeComponent(entityId, FollowTarget.class);
    }

    @Override
    public void flee() {
        //PrioritySteering<Vector2> prioritySteering = new PrioritySteering(this, 0.0001f);

        // Get the nearest enemy
        // FIXME: method naming if truly nearest enemy, and perhaps we should flee from our assailant
//        CreatureControl target = getAttackTarget();
//
//        // Flee from the enemy
//        if (target != null) {
//            Flee<Vector2> flee = new Flee<>(this, target);
//            prioritySteering.add(flee);
//        }
        // FIXME: For now just flee towards the dungeon heart or random tiles
        Keeper keeper = levelInfo.getPlayer(getOwnerId());
        if (keeper != null && keeper.getDungeonHeartLocation() != null) {
            Point p = keeper.getDungeonHeartLocation();
            createNavigation(getCreatureCoordinates(), new Point(p.x - 2, p.y - 2), null);
        } else {
            navigateToRandomPoint();
        }

        // Try to find our dungeon heart etc. safety haven
        //setSteeringBehavior(prioritySteering);
    }

    @Override
    public boolean isAttacked() {
        // TODO:
        return false;
    }

    @Override
    public boolean isEnoughSleep() {
        double timeSpent = gameTimer.getGameTime() - entityData.getComponent(entityId, CreatureAi.class).stateStartTime;
        if (timeSpent >= creature.getAttributes().getTimeSleep()) {

            // Hmm, I don't know if this is the right place to do this, but works for now
            CreatureSleep creatureSleep = entityData.getComponent(entityId, CreatureSleep.class);
            entityData.setComponent(entityId, new CreatureSleep(creatureSleep.lairObjectId, gameTimer.getGameTime(), creatureSleep.sleepStartTime));
            return true;
        }
        return false;
    }

    @Override
    public boolean isIncapacitated() {
        return isIncapacitated(entityData, entityId);
    }

    private boolean isIncapacitated(EntityId entityId) {
        return isIncapacitated(entityData, entityId);
    }

    private static boolean isIncapacitated(EntityData entityData, EntityId entityId) {
        Health health = entityData.getComponent(entityId, Health.class);
        if (health == null || entityData.getComponent(entityId, Unconscious.class) != null) {
            return true;
        }

        CreatureRecuperating recuperating = entityData.getComponent(entityId, CreatureRecuperating.class);
        if (recuperating != null) {
            return true;
        }

        CreatureFall creatureFall = entityData.getComponent(entityId, CreatureFall.class);
        if (creatureFall != null) {
            return true;
        }

        InHand inHand = entityData.getComponent(entityId, InHand.class);
        if (inHand != null) {
            return true;
        }

        return false;
    }

    private void initState() {
        stateMachine.changeState(entityData.getComponent(entityId, CreatureAi.class).getCreatureState());
    }

    @Override
    public boolean canFly() {
        Mobile mobile = entityData.getComponent(entityId, Mobile.class);
        return mobile.canFly;
    }

    @Override
    public boolean canWalkOnWater() {
        Mobile mobile = entityData.getComponent(entityId, Mobile.class);
        if (!mobile.canWalkOnWater) {

            // We need anyway to get out from water if we are tossed in such
            Vector3f pos = getPosition();
            if (pos != null) {
                return mapController.isWater(WorldUtils.vectorToPoint(pos));
            }
        }
        return mobile.canWalkOnWater;
    }

    @Override
    public boolean canWalkOnLava() {
        Mobile mobile = entityData.getComponent(entityId, Mobile.class);
        if (!mobile.canWalkOnLava) {

            // We need anyway to get out from lava if we are tossed in such
            Vector3f pos = getPosition();
            if (pos != null) {
                return mapController.isLava(WorldUtils.vectorToPoint(pos));
            }
        }
        return mobile.canWalkOnLava;
    }

    @Override
    public boolean canMoveDiagonally() {
        return true;
    }

    @Override
    public void start() {

    }

    @Override
    public void processTick(float tpf) {

        /**
         * Hmm, I'm not sure how to do this, this is not ideal either, how to
         * control the state machine outside the controller. Should it be
         * allowed and should we just check that the current state matches the
         * state in the entity component
         */
        //CreatureAi creatureAi = entityData.getComponent(entityId, CreatureAi.class);
        if (stateMachine.getCurrentState() == null) {
            initState();
        }

        /**
         * The creatures have these time motionless stuff in different states,
         * they seem to equal to kind of re-evaluate what to do. We should
         * figure out a proper way to utilize these. We could also use the
         * delayed telegram stuff. The hard part is just to kind of figure out
         * the motionless part and not re-send messages always etc. We could
         * probably go with pretty much event driven AI.
         */
        if (isStopped()) {
            motionless += tpf;
        } else {
            motionless = 0;
        }

        // Task timer
        TaskComponent taskComponent = entityData.getComponent(entityId, TaskComponent.class);
        if (taskComponent != null && taskComponent.taskStarted) {
            entityData.setComponent(entityId, new TaskComponent(taskComponent.taskId, taskComponent.targetEntity, taskComponent.targetLocation, taskComponent.taskType, taskComponent.taskDuration + tpf, taskComponent.taskStarted));
        }

        stateMachine.update();

        // Also change our state component
        CreatureAi creatureAi = entityData.getComponent(entityId, CreatureAi.class);
        if (creatureAi == null || stateMachine.getCurrentState() != creatureAi.getCreatureState()) {
            entityData.setComponent(entityId, new CreatureAi(gameTimer.getGameTime(), stateMachine.getCurrentState(), creature.getId()));
        }
    }

    @Override
    public boolean isTimeToReEvaluate() {

        // See that we have been motionless for enough time, per state
        // TODO: now just 5 seconds, it is the default for imps
        return motionless >= 5f;
    }

    @Override
    public void resetReEvaluationTimer() {
        motionless = 0;
    }

    @Override
    public void addGold(int amount) {
        synchronized (GameWorldController.GOLD_LOCK) {
            Gold gold = entityData.getComponent(entityId, Gold.class);

            // Drop excess gold, we can only carry so much
            int maxGoldCanAdd = Math.max(0, gold.maxGold - gold.gold);
            int goldToAdd = Math.min(amount, maxGoldCanAdd);
            int looseGold = amount - goldToAdd;

            if (goldToAdd > 0) {
                entityData.setComponent(entityId, new Gold(gold.gold + goldToAdd, gold.maxGold));
            }

            if (looseGold > 0) {
                Point coordinates = getCreatureCoordinates();
                objectsController.addLooseGold(getOwnerId(), coordinates.x, coordinates.y, looseGold, (int) gameSettings.get(Variable.MiscVariable.MiscType.MAX_GOLD_PILE_OUTSIDE_TREASURY).getValue());
            }
        }
    }

    @Override
    public int getGold() {
        return entityData.getComponent(entityId, Gold.class).gold;
    }

    @Override
    public int getMaxGold() {
        return entityData.getComponent(entityId, Gold.class).maxGold;
    }

    @Override
    public void substractGold(int amount) {
        synchronized (GameWorldController.GOLD_LOCK) {
            Gold gold = entityData.getComponent(entityId, Gold.class);
            entityData.setComponent(entityId, new Gold(gold.gold - amount, gold.maxGold));
        }
    }

    @Override
    public Point getLairLocation() {
        CreatureSleep creatureSleep = entityData.getComponent(entityId, CreatureSleep.class);
        if (creatureSleep != null && creatureSleep.lairObjectId != null) {
            Position position = entityData.getComponent(creatureSleep.lairObjectId, Position.class);
            if (position != null) {
                return WorldUtils.vectorToPoint(position.position);
            }
        }
        return null;
    }

    @Override
    public boolean isUnconscious() {
        return entityData.getComponent(entityId, Unconscious.class) != null;
    }

    @Override
    public Point getCreatureCoordinates() {
        return getCreatureCoordinates(entityData, entityId);
    }

    private static Point getCreatureCoordinates(EntityData entityData, EntityId entity) {
        return WorldUtils.vectorToPoint(getPosition(entityData, entity));
    }

    @Override
    public void setAssignedTask(Task task) {

        // Unassign previous task
        unassingCurrentTask();

        //workNavigationRequired = true;
        entityData.setComponent(entityId, new TaskComponent(task.getId(), task.getTaskTarget(), task.getTaskLocation(), task.getTaskType(), 0.0f, false));
    }

    @Override
    public Creature getCreature() {
        return creature;
    }

    @Override
    public void stopCreature() {
        entityData.removeComponent(entityId, Navigation.class);
    }

    @Override
    public int getObjectiveTargetActionPointId() {
        Objective creatureObjective = entityData.getComponent(entityId, Objective.class);
        if (creatureObjective != null) {
            return creatureObjective.actionPointId;
        }
        return 0;
    }

    @Override
    public void setObjectiveTargetActionPointId(int actionPointId) {
        Objective creatureObjective = entityData.getComponent(entityId, Objective.class);
        entityData.setComponent(entityId, new Objective((creatureObjective != null ? creatureObjective.objective : null), (creatureObjective != null ? creatureObjective.objectiveTargetPlayerId : 0), actionPointId));
    }

    @Override
    public Thing.HeroParty.Objective getObjective() {
        Objective creatureObjective = entityData.getComponent(entityId, Objective.class);
        if (creatureObjective != null) {
            return creatureObjective.objective;
        }
        return null;
    }

    @Override
    public void setObjective(Thing.HeroParty.Objective objective) {
        Objective creatureObjective = entityData.getComponent(entityId, Objective.class);
        entityData.setComponent(entityId, new Objective(objective, (creatureObjective != null ? creatureObjective.objectiveTargetPlayerId : 0), (creatureObjective != null ? creatureObjective.actionPointId : 0)));
    }

    @Override
    public boolean isDead() {
        Health health = entityData.getComponent(entityId, Health.class);
        return health == null;
    }

    @Override
    public boolean isImprisoned() {
        return isImprisoned(entityData, entityId);
    }

    @Override
    public boolean isTortured() {
        return isTortured(entityData, entityId);
    }

    private static boolean isImprisoned(EntityData entityData, EntityId entity) {
        CreatureImprisoned imprisoned = entityData.getComponent(entity, CreatureImprisoned.class);
        return imprisoned != null;
    }

    private static boolean isTortured(EntityData entityData, EntityId entity) {
        CreatureTortured tortured = entityData.getComponent(entity, CreatureTortured.class);
        return tortured != null;
    }

    @Override
    public boolean isStunned() {
        return stateMachine.isInState(CreatureState.STUNNED);
    }

    @Override
    public int getLevel() {
        CreatureExperience creatureExperience = entityData.getComponent(entityId, CreatureExperience.class);
        return creatureExperience.level;
    }

    @Override
    public boolean isSlapped() {
        Slapped creatureSlapped = entityData.getComponent(entityId, Slapped.class);
        return creatureSlapped != null;
    }

    @Override
    public boolean isPortalGemInPosession() {
        PortalGem portalGem = entityData.getComponent(entityId, PortalGem.class);
        return portalGem != null;
    }
    @Override
    public void attachPortalGem() {
        entityData.setComponent(entityId, new PortalGem());
    }

    @Override
    public void setObjectiveTargetPlayerId(short playerId) {
        Objective creatureObjective = entityData.getComponent(entityId, Objective.class);
        entityData.setComponent(entityId, new Objective((creatureObjective != null ? creatureObjective.objective : null), playerId, (creatureObjective != null ? creatureObjective.actionPointId : 0)));
    }

    @Override
    public short getObjectiveTargetPlayerId() {
        Objective creatureObjective = entityData.getComponent(entityId, Objective.class);
        if (creatureObjective != null) {
            return creatureObjective.objectiveTargetPlayerId;
        }

        return -1;
    }

    @Override
    public void setPlayerObjective(ObjectiveType objective) {
        if (objective == null) {
            entityData.removeComponent(entityId, PlayerObjective.class);
        } else {
            entityData.setComponent(entityId, new PlayerObjective(objective));
        }
    }

    @Override
    public void setCreatureLair(EntityId lairId) {
        CreatureSleep creatureSleep = entityData.getComponent(entityId, CreatureSleep.class);
        entityData.setComponent(entityId, new CreatureSleep(lairId, creatureSleep.lastSleepTime, creatureSleep.sleepStartTime));
    }

    @Override
    public void sleep() {
        entityData.setComponent(entityId, new CreatureRecuperating(gameTimer.getGameTime(), gameTimer.getGameTime()));
        if (isNeedForRecuperating()) {
            // entityData.setComponent(entityId, new CreatureAi(gameTimer.getGameTime(), CreatureState.RECUPERATING, creature.getCreatureId()));
            stateMachine.changeState(CreatureState.RECUPERATING);
        } else {
            CreatureSleep creatureSleep = entityData.getComponent(entityId, CreatureSleep.class);
            entityData.setComponent(entityId, new CreatureSleep(creatureSleep.lairObjectId, creatureSleep.lastSleepTime, gameTimer.getGameTime()));
            // entityData.setComponent(entityId, new CreatureAi(gameTimer.getGameTime(), CreatureState.SLEEPING, creature.getCreatureId()));
            stateMachine.changeState(CreatureState.SLEEPING);
        }
    }

    private boolean isAlly(EntityId entity) {
        Owner otherOwner = entityData.getComponent(entity, Owner.class);
        if (otherOwner != null) {
            Keeper keeper = levelInfo.getPlayer(getOwnerId());
            if (keeper != null) {
                return keeper.isAlly(otherOwner.ownerId);
            }
        }

        return false;
    }

    private Creature.FightStyle getEntityFightingStyle(EntityId entity) {
        CreatureComponent otherCreature = entityData.getComponent(entity, CreatureComponent.class);
        if (otherCreature != null) {
            return levelInfo.getLevelData().getCreature(otherCreature.creatureId).getFightStyle();
        }

        return null;
    }

    private boolean isEnemy(EntityId entity) {
        Owner otherOwner = entityData.getComponent(entity, Owner.class);
        if (otherOwner != null) {
            Keeper keeper = levelInfo.getPlayer(getOwnerId());
            if (keeper != null) {
                return keeper.isEnemy(otherOwner.ownerId);
            }
        }

        return false;
    }

    private int getThreat(EntityId entity) {
        Threat threat = entityData.getComponent(entity, Threat.class);
        if (threat != null) {
            return threat.threat;
        }

        return 0;
    }

    private boolean isFleeing(EntityId entity) {
        CreatureAi creatureAi = entityData.getComponent(entity, CreatureAi.class);
        if (creatureAi != null) {
            return creatureAi.getCreatureState() == CreatureState.FLEE;
        }

        return false;
    }

    @Override
    public void imprison(short playerId, EntityId roomId) {

        // Return health to 20%
        Health health = entityData.getComponent(entityId, Health.class);
        if (health == null) {
            logger.log(Level.INFO, "Tried to imprison me but I don't have any health left!");
            return;
        }

        entityData.setComponent(entityId, new Health((int) Math.floor(health.maxHealth * 0.2f), health.maxHealth));
        entityData.removeComponent(entityId, Unconscious.class);
        entityData.setComponent(entityId, new CreatureImprisoned(gameTimer.getGameTime(), gameTimer.getGameTime()));
        entityData.setComponent(entityId, new Stored(roomId, AbstractRoomController.ObjectType.PRISONER));

        // Switch the control to the imprisoning player
        Owner owner = entityData.getComponent(entityId, Owner.class);
        entityData.setComponent(entityId, new Owner(owner.ownerId, playerId));

        stateMachine.changeState(CreatureState.IMPRISONED);
    }

    @Override
    public boolean isStateTimeExceeded() {
        double timeSpent = gameTimer.getGameTime() - entityData.getComponent(entityId, CreatureAi.class).stateStartTime;
        double stateTargetTime;

        switch (stateMachine.getCurrentState()) {
            case STUNNED -> {
                // Hmm, this might actually be the level variable, the stun seems to be the time fallen when dropped
                stateTargetTime = entityData.getComponent(entityId, CreatureComponent.class).stunDuration;
            }
            case FALLEN -> {
                stateTargetTime = entityData.getComponent(entityId, CreatureComponent.class).stunDuration;
            }
            case GETTING_UP -> {
                stateTargetTime = getAnimationTime(creature, Creature.AnimationType.GET_UP);
            }
            case ENTERING_DUNGEON -> {
                stateTargetTime = getAnimationTime(creature, Creature.AnimationType.ENTRANCE);
            }
            case MELEE_ATTACK -> {
                stateTargetTime = getAnimationTime(creature, Creature.AnimationType.MELEE_ATTACK);
            }
            case CAST_SPELL -> {
                stateTargetTime = getAnimationTime(creature, Creature.AnimationType.CAST_SPELL);
            }
            case EATING -> {
                stateTargetTime = getAnimationTime(creature, Creature.AnimationType.EATING);
            }
            case FLEE -> {
                // I couldn't find a variable for this, so lets use this for now
                stateTargetTime = gameSettings.get(Variable.MiscVariable.MiscType.IMP_IDLE_DELAY_BEFORE_REEVALUATION_SECONDS).getValue();
            }
            default -> {
                stateTargetTime = Double.MAX_VALUE;
            }
        }

        return stateTargetTime < timeSpent;
    }

    private static double getAnimationTime(Creature creature, Creature.AnimationType animation) {
        // TODO: we could cache and calculate these for all centrally, also include the starting and ending animation
        ArtResource animationResource = creature.getAnimation(animation);
        if (animationResource == null) {
            return 0;
        }

        int frames = animationResource.getData("frames");
        int fps = animationResource.getData("fps");

        return frames / (double) fps;
    }

    @Override
    public boolean isClaimed() {
        return Player.NEUTRAL_PLAYER_ID != getOwnerId();
    }

    @Override
    public boolean isHungry() {
        CreatureHunger creatureHunger = entityData.getComponent(entityId, CreatureHunger.class);
        if (creatureHunger != null) {
            if (creatureHunger.amountNeeded > 0) {
                return true;
            } else if (gameTimer.getGameTime() - creatureHunger.lastEatTime >= creature.getAttributes().getHungerRate()) {

                // We are hungry now, mark the amount of food we need
                makeHungry(creatureHunger);

                return true;
            }
        }

        return false;
    }

    @Override
    public boolean goToEat() {
        return taskManager.assignEatTask(this);
    }

    @Override
    public void eat(IEntityController target) {

        // Ok, first we destroy the entity we are eating so that nobody else gets it
        Health health = entityData.getComponent(target.getEntityId(), Health.class);
        if (health != null) {
            target.remove();

            stateMachine.changeState(CreatureState.EATING);
        }
    }

    @Override
    public void sate() {
        CreatureHunger creatureHunger = entityData.getComponent(entityId, CreatureHunger.class);
        if (creatureHunger.amountNeeded == 1) {

            // We are full
            entityData.setComponent(entityId, new CreatureHunger(gameTimer.getGameTime(), 0));
        } else if (creatureHunger.amountNeeded > 0) {
            entityData.setComponent(entityId, new CreatureHunger(creatureHunger.lastEatTime, creatureHunger.amountNeeded - 1));
        }

        // Increase health
        setDamage(-creature.getAttributes().getHpFromChicken());
    }

    @Override
    public void makeHungry() {
        CreatureHunger creatureHunger = entityData.getComponent(entityId, CreatureHunger.class);
        makeHungry(creatureHunger);
    }

    private void makeHungry(CreatureHunger creatureHunger) {
        CreatureComponent creatureComponent = entityData.getComponent(entityId, CreatureComponent.class);
        entityData.setComponent(entityId, new CreatureHunger(creatureHunger.lastEatTime, creatureComponent.hungerFill));
    }

    @Override
    public int getResearchPerSecond() {
        CreatureComponent creatureComponent = entityData.getComponent(entityId, CreatureComponent.class);
        return creatureComponent.researchPerSecond;
    }

    @Override
    public void giveObject(IObjectController object) {
        synchronized (GameWorldController.GOLD_LOCK) {
            if (object.getType() == AbstractRoomController.ObjectType.GOLD) {
                addGold(entityData.getComponent(object.getEntityId(), Gold.class).gold);
                entityData.removeComponent(object.getEntityId(), Gold.class);
                object.remove();
            } else {
                logger.log(Level.WARNING, "Object {0} receiving not specified!", object.getType());
            }
        }
    }

    @Override
    public void stopRecuperating() {
        entityData.removeComponent(entityId, CreatureRecuperating.class);
    }

    @Override
    public boolean isRecuperating() {
        return entityData.getComponent(entityId, CreatureRecuperating.class) != null;
    }

    @Override
    public void setPossession(boolean possessed) {
        if (possessed) {
            startPossession();
        } else {
            endPossession();
        }
    }

    private void startPossession() {
        CreatureComponent creatureComponent = entityData.getComponent(entityId, CreatureComponent.class);
        int manaDrain = creatureComponent != null ? creatureComponent.posessionManaCost : 0;
        Mana mana = entityData.getComponent(entityId, Mana.class);
        entityData.setComponent(entityId, new Mana(mana != null ? -manaDrain - mana.manaGeneration : -manaDrain));
        entityData.setComponent(entityId, new Possessed(manaDrain, gameTimer.getGameTime()));
        entityData.removeComponent(entityId, CreatureAi.class);
        entityData.removeComponent(entityId, Navigation.class);
    }

    private void endPossession() {
        entityData.setComponent(entityId, new CreatureAi(gameTimer.getGameTime(), CreatureState.IDLE, getCreature().getCreatureId()));

        // Return the mana flow
        Possessed possessed = entityData.getComponent(entityId, Possessed.class);
        Mana mana = entityData.getComponent(entityId, Mana.class);
        int manaGeneration = mana.manaGeneration + possessed.manaDrain;
        if (manaGeneration == 0) {
            entityData.removeComponent(entityId, Mana.class);
        } else {
            entityData.setComponent(entityId, new Mana(manaGeneration));
        }
    }

    @Override
    public void convertCreature(short playerId) {
        convertCreature(entityId, playerId);
    }

    private void convertCreature(EntityId entity, short playerId) {
        removeFromRoomStrorage();

        // Remove our lair from the last player
        removeLair();

        // Set new owner
        entityData.setComponent(entity, new Owner(playerId, playerId));

        // Remove good player stuff
        entityData.removeComponent(entity, Objective.class);

        // Reset AI
        getStateMachine().changeState(CreatureState.IDLE);
    }

}
