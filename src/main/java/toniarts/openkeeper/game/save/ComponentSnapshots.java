/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import java.util.function.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.controller.chicken.ChickenState;
import toniarts.openkeeper.game.controller.room.AbstractRoomController;
import toniarts.openkeeper.game.data.ObjectiveType;
import toniarts.openkeeper.game.data.ResearchableType;
import toniarts.openkeeper.game.task.TaskType;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.GameObject;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.tools.convert.map.Tile;
import static toniarts.openkeeper.game.save.SaveValues.*;

/**
 * Explicit, versioned schemas for detached ECS state. Capture under the
 * simulation boundary, then encode outside it. These records are only one
 * section of a future saved game; they do not restore controllers or sessions.
 */
public final class ComponentSnapshots {
    static final int MAGIC = 0x4d4b4353; // MKCS
    static final int FORMAT_VERSION = 1;
    static final int MAX_COMPONENT_BYTES = 1_048_576;
    private static final Map<String, Schema<?>> BY_ID = new LinkedHashMap<>();
    private static final Map<Class<? extends EntityComponent>, Schema<?>> BY_CLASS = new LinkedHashMap<>();

    /** Logically immutable: mutable copied values are private and never escape. */
    public static final class Snapshot {
        private final Schema<?> schema;
        private final Object[] values;
        private Snapshot(Schema<?> schema, Object[] values) {this.schema = schema;this.values = values;}
        public String type() {return schema.id;}
        public int version() {return schema.version;}
        public Set<EntityId> referencedEntityIds() {
            Set<EntityId> result = new HashSet<>();
            for (int i = 0; i < values.length; i++) schema.fields.get(i).references(values[i], result);
            return Set.copyOf(result);
        }
    }

    public static Set<Class<? extends EntityComponent>> registeredTypes() {return Set.copyOf(BY_CLASS.keySet());}
    static int typeCount() {return BY_CLASS.size();}

    /** Copies every value, including mutable collections, points and vectors. */
    public static Snapshot capture(EntityComponent component) throws IOException {
        Objects.requireNonNull(component);
        var schema = BY_CLASS.get(component.getClass());
        if (schema == null) throw new IOException("Unsupported component: " + component.getClass().getName());
        return schema.capture(component);
    }

    /** Returns a fresh independent component on every call. Does not modify a live world. */
    public static EntityComponent restore(Snapshot snapshot) throws IOException {
        return snapshot.schema.restore(snapshot.values);
    }

    public static byte[] encode(Snapshot snapshot) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new OutputStream() {
            private int size;
            public void write(int value) throws IOException {reserve(1);bytes.write(value);}
            public void write(byte[] b, int offset, int length) throws IOException {reserve(length);bytes.write(b, offset, length);}
            private void reserve(int length) throws IOException {
                if (length > MAX_COMPONENT_BYTES - size) throw new IOException("Component snapshot exceeds size limit");
                size += length;
            }
        })) {
            out.writeInt(MAGIC);out.writeInt(FORMAT_VERSION);
            TEXT.write(out, snapshot.type());out.writeInt(snapshot.version());
            for (int i = 0; i < snapshot.values.length; i++) snapshot.schema.fields.get(i).write(out, snapshot.values[i]);
        }
        return bytes.toByteArray();
    }

    /** Validates the record before exposing state; unknown schemas and trailing bytes fail. */
    public static Snapshot decode(byte[] encoded) throws IOException {
        count(encoded.length, MAX_COMPONENT_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a MacKeeper component snapshot");
            if (in.readInt() != FORMAT_VERSION) throw new IOException("Unsupported component container version");
            String id = TEXT.read(in);Schema<?> schema = BY_ID.get(id);
            if (schema == null) throw new IOException("Unknown component type: " + id);
            if (in.readInt() != schema.version) throw new IOException("Unsupported schema version for " + id);
            Object[] values = new Object[schema.fields.size()];
            for (int i = 0; i < values.length; i++) values[i] = schema.fields.get(i).read(in);
            if (in.read() != -1) throw new IOException("Trailing component data");
            return new Snapshot(schema, values);
        }
    }

    // Used by schema-coverage tests; field names are explicit compatibility
    // identifiers, not discovered reflectively or read from an input class.
    static Set<String> fieldNames(Class<?> type) {
        Set<String> names = new HashSet<>();for (var field : BY_CLASS.get(type).fields) names.add(field.name);
        return Set.copyOf(names);
    }

    private static final class Field<C, T> {
        final String name;
        final Value<T> value;
        final Function<C, T> getter;
        final BiConsumer<C, T> setter;
        Field(String name, Value<T> value, Function<C, T> getter, BiConsumer<C, T> setter) {
            this.name = name;this.value = value;this.getter = getter;this.setter = setter;
        }
        Object capture(C component) throws IOException {return value.copy(getter.apply(component));}
        @SuppressWarnings("unchecked") Object copy(Object v) throws IOException {return value.copy((T) v);}
        @SuppressWarnings("unchecked") void set(C c, Object v) {setter.accept(c, (T) v);}
        @SuppressWarnings("unchecked") void write(DataOutput out, Object v) throws IOException {value.write(out, (T) v);}
        Object read(DataInput in) throws IOException {return value.read(in);}
        @SuppressWarnings("unchecked") void references(Object v, Set<EntityId> ids) {value.references((T) v, ids);}
    }

    private static final class Schema<C extends EntityComponent> {
        final String id;
        final int version;
        final Class<C> type;
        final Supplier<C> factory;
        final Function<Object[], C> constructor;
        List<Field<C, ?>> fields = new ArrayList<>();
        Schema(String id, int version, Class<C> type, Supplier<C> factory, Function<Object[], C> constructor) {
            this.id = id;this.version = version;this.type = type;this.factory = factory;this.constructor = constructor;
        }
        <T> Schema<C> field(String name, Value<T> value, Function<C, T> getter, BiConsumer<C, T> setter) {
            fields.add(new Field<>(name, value, getter, setter));return this;
        }
        Snapshot capture(EntityComponent component) throws IOException {
            Object[] values = new Object[fields.size()];
            try {for (int i = 0; i < values.length; i++) values[i] = fields.get(i).capture(type.cast(component));}
            catch (RuntimeException e) {throw new IOException("Invalid live component: " + id, e);}
            return new Snapshot(this, values);
        }
        C restore(Object[] values) throws IOException {
            Object[] copies = new Object[values.length];
            for (int i = 0; i < values.length; i++) copies[i] = fields.get(i).copy(values[i]);
            if (constructor != null) return constructor.apply(copies);
            C result = factory.get();
            for (int i = 0; i < copies.length; i++) fields.get(i).set(result, copies[i]);
            return result;
        }
        void register() {
            fields = List.copyOf(fields);
            if (BY_ID.putIfAbsent(id, this) != null || BY_CLASS.putIfAbsent(type, this) != null) {
                throw new IllegalStateException("Duplicate component save schema: " + id);
            }
        }
    }

    private static <C extends EntityComponent> Schema<C> schema(String id, int version, Class<C> type, Supplier<C> factory) {
        return new Schema<>(id, version, type, factory, null);
    }

    // Explicit schema registrations follow. Changing any field's wire meaning
    // or order requires a version change and, when supported, a migration.
    static {
        schema("attack-target", 1, AttackTarget.class, AttackTarget::new)
                .field("entityId", nullable(ENTITY_ID), c -> c.entityId, (c, v) -> c.entityId = v)
                .register();
        new Schema<>("chicken-ai", 1, ChickenAi.class, null,
                v -> new ChickenAi((Double) v[0], (ChickenState) v[1]))
                .field("stateStartTime", DOUBLE, c -> c.stateStartTime, null)
                .field("chickenState", enumeration(ChickenState.class), ChickenAi::getChickenState, null)
                .register();
        schema("chicken-generator", 1, ChickenGenerator.class, ChickenGenerator::new)
                .field("lastSpawnTime", DOUBLE, c -> c.lastSpawnTime, (c, v) -> c.lastSpawnTime = v)
                .register();
        new Schema<>("creature-ai", 1, CreatureAi.class, null,
                v -> new CreatureAi((Double) v[0], (CreatureState) v[1], (Short) v[2]))
                .field("stateStartTime", DOUBLE, c -> c.stateStartTime, null)
                .field("creatureState", enumeration(CreatureState.class), CreatureAi::getCreatureState, null)
                .field("creatureId", SHORT, c -> c.creatureId, null)
                .register();
        schema("creature-component", 1, CreatureComponent.class, CreatureComponent::new)
                .field("name", nullable(TEXT), c -> c.name, (c, v) -> c.name = v)
                .field("bloodType", nullable(TEXT), c -> c.bloodType, (c, v) -> c.bloodType = v)
                .field("height", FLOAT, c -> c.height, (c, v) -> c.height = v)
                .field("fear", INT, c -> c.fear, (c, v) -> c.fear = v)
                .field("pay", INT, c -> c.pay, (c, v) -> c.pay = v)
                .field("maxGoldHeld", INT, c -> c.maxGoldHeld, (c, v) -> c.maxGoldHeld = v)
                .field("hungerFill", INT, c -> c.hungerFill, (c, v) -> c.hungerFill = v)
                .field("manaGenPrayer", INT, c -> c.manaGenPrayer, (c, v) -> c.manaGenPrayer = v)
                .field("researchPerSecond", INT, c -> c.researchPerSecond, (c, v) -> c.researchPerSecond = v)
                .field("manufacturePerSecond", INT, c -> c.manufacturePerSecond, (c, v) -> c.manufacturePerSecond = v)
                .field("decomposeValue", INT, c -> c.decomposeValue, (c, v) -> c.decomposeValue = v)
                .field("speed", FLOAT, c -> c.speed, (c, v) -> c.speed = v)
                .field("runSpeed", FLOAT, c -> c.runSpeed, (c, v) -> c.runSpeed = v)
                .field("tortureTimeToConvert", FLOAT, c -> c.tortureTimeToConvert, (c, v) -> c.tortureTimeToConvert = v)
                .field("posessionManaCost", INT, c -> c.posessionManaCost, (c, v) -> c.posessionManaCost = v)
                .field("stunDuration", FLOAT, c -> c.stunDuration, (c, v) -> c.stunDuration = v)
                .field("worker", BOOLEAN, c -> c.worker, (c, v) -> c.worker = v)
                .field("creatureId", SHORT, c -> c.creatureId, (c, v) -> c.creatureId = v)
                .field("diesInstantly", BOOLEAN, c -> c.diesInstantly, (c, v) -> c.diesInstantly = v)
                .register();
        schema("creature-efficiency", 1, CreatureEfficiency.class, CreatureEfficiency::new)
                .field("efficiencyPercentage", INT, c -> c.efficiencyPercentage, (c, v) -> c.efficiencyPercentage = v)
                .register();
        schema("creature-experience", 1, CreatureExperience.class, CreatureExperience::new)
                .field("level", INT, c -> c.level, (c, v) -> c.level = v)
                .field("experience", INT, c -> c.experience, (c, v) -> c.experience = v)
                .field("experienceToNextLevel", INT, c -> c.experienceToNextLevel, (c, v) -> c.experienceToNextLevel = v)
                .field("experiencePerSecond", INT, c -> c.experiencePerSecond, (c, v) -> c.experiencePerSecond = v)
                .field("experiencePerSecondTraining", INT, c -> c.experiencePerSecondTraining, (c, v) -> c.experiencePerSecondTraining = v)
                .register();
        schema("creature-fall", 1, CreatureFall.class, CreatureFall::new)
                .register();
        schema("creature-generator", 1, CreatureGenerator.class, CreatureGenerator::new)
                .field("entrance", nullable(POINT), c -> c.entrance, (c, v) -> c.entrance = v)
                .field("lastSpawnTime", DOUBLE, c -> c.lastSpawnTime, (c, v) -> c.lastSpawnTime = v)
                .register();
        schema("creature-hunger", 1, CreatureHunger.class, CreatureHunger::new)
                .field("lastEatTime", DOUBLE, c -> c.lastEatTime, (c, v) -> c.lastEatTime = v)
                .field("amountNeeded", INT, c -> c.amountNeeded, (c, v) -> c.amountNeeded = v)
                .register();
        schema("creature-imprisoned", 1, CreatureImprisoned.class, CreatureImprisoned::new)
                .field("startTime", DOUBLE, c -> c.startTime, (c, v) -> c.startTime = v)
                .field("healthCheckTime", DOUBLE, c -> c.healthCheckTime, (c, v) -> c.healthCheckTime = v)
                .register();
        schema("creature-melee-attack", 1, CreatureMeleeAttack.class, CreatureMeleeAttack::new)
                .field("attactStartTime", nullable(DOUBLE), c -> c.attactStartTime, (c, v) -> c.attactStartTime = v)
                .field("rechargeTime", FLOAT, c -> c.rechargeTime, (c, v) -> c.rechargeTime = v)
                .field("range", FLOAT, c -> c.range, (c, v) -> c.range = v)
                .field("attackType", enumeration(Creature.AttackType.class), CreatureMeleeAttack::getAttackType, (c, v) -> c.attackType = v.ordinal())
                .field("damage", INT, c -> c.damage, (c, v) -> c.damage = v)
                .register();
        schema("creature-mood", 1, CreatureMood.class, CreatureMood::new)
                .field("general", INT, c -> c.general, (c, v) -> c.general = v)
                .field("noFood", INT, c -> c.noFood, (c, v) -> c.noFood = v)
                .field("noLair", INT, c -> c.noLair, (c, v) -> c.noLair = v)
                .field("noWork", INT, c -> c.noWork, (c, v) -> c.noWork = v)
                .field("noPay", INT, c -> c.noPay, (c, v) -> c.noPay = v)
                .field("other", INT, c -> c.other, (c, v) -> c.other = v)
                .register();
        schema("creature-recuperating", 1, CreatureRecuperating.class, CreatureRecuperating::new)
                .field("startTime", DOUBLE, c -> c.startTime, (c, v) -> c.startTime = v)
                .field("healthCheckTime", DOUBLE, c -> c.healthCheckTime, (c, v) -> c.healthCheckTime = v)
                .register();
        schema("creature-salary", 1, CreatureSalary.class, CreatureSalary::new)
                .field("employer", SHORT, c -> c.employer, (c, v) -> c.employer = v)
                .field("owed", LONG, c -> c.owed, (c, v) -> c.owed = v)
                .field("advance", LONG, c -> c.advance, (c, v) -> c.advance = v)
                .field("unpaidSince", DOUBLE, c -> c.unpaidSince, (c, v) -> c.unpaidSince = v)
                .register();
        schema("creature-sleep", 1, CreatureSleep.class, CreatureSleep::new)
                .field("lairObjectId", nullable(ENTITY_ID), c -> c.lairObjectId, (c, v) -> c.lairObjectId = v)
                .field("lastSleepTime", DOUBLE, c -> c.lastSleepTime, (c, v) -> c.lastSleepTime = v)
                .field("sleepStartTime", DOUBLE, c -> c.sleepStartTime, (c, v) -> c.sleepStartTime = v)
                .register();
        schema("creature-spell", 1, CreatureSpell.class, CreatureSpell::new)
                .field("attactStartTime", nullable(DOUBLE), c -> c.attactStartTime, (c, v) -> c.attactStartTime = v)
                .field("rechargeTime", FLOAT, c -> c.rechargeTime, (c, v) -> c.rechargeTime = v)
                .field("range", FLOAT, c -> c.range, (c, v) -> c.range = v)
                .field("creatureSpellId", SHORT, c -> c.creatureSpellId, (c, v) -> c.creatureSpellId = v)
                .field("creatureId", nullable(ENTITY_ID), c -> c.creatureId, (c, v) -> c.creatureId = v)
                .register();
        schema("creature-spells", 1, CreatureSpells.class, CreatureSpells::new)
                .field("creatureSpells", nullable(list(ENTITY_ID)), c -> c.creatureSpells, (c, v) -> c.creatureSpells = v)
                .register();
        schema("creature-tortured", 1, CreatureTortured.class, CreatureTortured::new)
                .field("timeTortured", DOUBLE, c -> c.timeTortured, (c, v) -> c.timeTortured = v)
                .field("tortureCheckTime", DOUBLE, c -> c.tortureCheckTime, (c, v) -> c.tortureCheckTime = v)
                .field("healthCheckTime", DOUBLE, c -> c.healthCheckTime, (c, v) -> c.healthCheckTime = v)
                .register();
        schema("creature-view-state", 1, CreatureViewState.class, CreatureViewState::new)
                .field("creatureId", SHORT, c -> c.creatureId, (c, v) -> c.creatureId = v)
                .field("stateStartTime", DOUBLE, c -> c.stateStartTime, (c, v) -> c.stateStartTime = v)
                .field("state", nullable(enumeration(Creature.AnimationType.class)), c -> c.state, (c, v) -> c.state = v)
                .register();
        schema("damage", 1, Damage.class, Damage::new)
                .field("damage", INT, c -> c.damage, (c, v) -> c.damage = v)
                .field("sourcePlayerId", SHORT, c -> c.sourcePlayerId, (c, v) -> c.sourcePlayerId = v)
                .register();
        schema("death", 1, Death.class, Death::new)
                .field("startTime", DOUBLE, c -> c.startTime, (c, v) -> c.startTime = v)
                .register();
        schema("decay", 1, Decay.class, Decay::new)
                .field("startTime", DOUBLE, c -> c.startTime, (c, v) -> c.startTime = v)
                .field("duration", DOUBLE, c -> c.duration, (c, v) -> c.duration = v)
                .register();
        schema("door-component", 1, DoorComponent.class, DoorComponent::new)
                .field("doorId", SHORT, c -> c.doorId, (c, v) -> c.doorId = v)
                .field("locked", BOOLEAN, c -> c.locked, (c, v) -> c.locked = v)
                .field("blueprint", BOOLEAN, c -> c.blueprint, (c, v) -> c.blueprint = v)
                .register();
        schema("door-view-state", 1, DoorViewState.class, DoorViewState::new)
                .field("doorId", SHORT, c -> c.doorId, (c, v) -> c.doorId = v)
                .field("locked", BOOLEAN, c -> c.locked, (c, v) -> c.locked = v)
                .field("blueprint", BOOLEAN, c -> c.blueprint, (c, v) -> c.blueprint = v)
                .field("open", BOOLEAN, c -> c.open, (c, v) -> c.open = v)
                .register();
        schema("dungeon-heart", 1, DungeonHeart.class, DungeonHeart::new)
                .register();
        schema("fearless", 1, Fearless.class, Fearless::new)
                .field("startTime", nullable(DOUBLE), c -> c.startTime, (c, v) -> c.startTime = v)
                .register();
        schema("follow-target", 1, FollowTarget.class, FollowTarget::new)
                .field("entityId", nullable(ENTITY_ID), c -> c.entityId, (c, v) -> c.entityId = v)
                .register();
        schema("food", 1, Food.class, Food::new)
                .register();
        schema("gold", 1, Gold.class, Gold::new)
                .field("gold", INT, c -> c.gold, (c, v) -> c.gold = v)
                .field("maxGold", INT, c -> c.maxGold, (c, v) -> c.maxGold = v)
                .register();
        schema("hauled-by", 1, HauledBy.class, HauledBy::new)
                .field("entityId", nullable(ENTITY_ID), c -> c.entityId, (c, v) -> c.entityId = v)
                .register();
        schema("health", 1, Health.class, Health::new)
                .field("health", INT, c -> c.health, (c, v) -> c.health = v)
                .field("maxHealth", INT, c -> c.maxHealth, (c, v) -> c.maxHealth = v)
                .register();
        schema("imp-generator", 1, ImpGenerator.class, ImpGenerator::new)
                .field("entrance", nullable(POINT), c -> c.entrance, (c, v) -> c.entrance = v)
                .field("lastSpawnTime", DOUBLE, c -> c.lastSpawnTime, (c, v) -> c.lastSpawnTime = v)
                .register();
        schema("in-hand", 1, InHand.class, InHand::new)
                .field("index", INT, c -> c.index, (c, v) -> c.index = v)
                .field("playerId", SHORT, c -> c.playerId, (c, v) -> c.playerId = v)
                .field("type", nullable(enumeration(ViewType.class)), c -> c.type, (c, v) -> c.type = v)
                .field("id", SHORT, c -> c.id, (c, v) -> c.id = v)
                .register();
        schema("interaction", 1, Interaction.class, Interaction::new)
                .field("interactable", BOOLEAN, c -> c.interactable, (c, v) -> c.interactable = v)
                .field("slappable", BOOLEAN, c -> c.slappable, (c, v) -> c.slappable = v)
                .field("pickUppable", BOOLEAN, c -> c.pickUppable, (c, v) -> c.pickUppable = v)
                .field("canBeDroppedOnAnyLand", BOOLEAN, c -> c.canBeDroppedOnAnyLand, (c, v) -> c.canBeDroppedOnAnyLand = v)
                .field("dieWhenSlapped", BOOLEAN, c -> c.dieWhenSlapped, (c, v) -> c.dieWhenSlapped = v)
                .register();
        schema("last-damage-source", 1, LastDamageSource.class, LastDamageSource::new)
                .field("playerId", SHORT, c -> c.playerId, (c, v) -> c.playerId = v)
                .register();
        schema("mana", 1, Mana.class, Mana::new)
                .field("manaGeneration", INT, c -> c.manaGeneration, (c, v) -> c.manaGeneration = v)
                .register();
        schema("map-tile", 1, MapTile.class, MapTile::new)
                .field("selection", nullable(PLAYER_FLAGS), c -> c.selection, (c, v) -> c.selection = v)
                .field("flashing", nullable(PLAYER_FLAGS), c -> c.flashing, (c, v) -> c.flashing = v)
                .field("randomTextureIndex", INT, c -> c.randomTextureIndex, (c, v) -> c.randomTextureIndex = v)
                .field("terrainId", SHORT, c -> c.terrainId, (c, v) -> c.terrainId = v)
                .field("bridgeTerrainType", nullable(enumeration(Tile.BridgeTerrainType.class)), c -> c.bridgeTerrainType, (c, v) -> c.bridgeTerrainType = v)
                .field("p", nullable(POINT), c -> c.p, (c, v) -> c.p = v)
                .field("index", INT, c -> c.index, (c, v) -> c.index = v)
                .field("room", nullable(ENTITY_ID), c -> c.room, (c, v) -> c.room = v)
                .register();
        schema("mobile", 1, Mobile.class, Mobile::new)
                .field("canFly", BOOLEAN, c -> c.canFly, (c, v) -> c.canFly = v)
                .field("canWalkOnWater", BOOLEAN, c -> c.canWalkOnWater, (c, v) -> c.canWalkOnWater = v)
                .field("canWalkOnLava", BOOLEAN, c -> c.canWalkOnLava, (c, v) -> c.canWalkOnLava = v)
                .field("maxSpeed", FLOAT, c -> c.maxSpeed, (c, v) -> c.maxSpeed = v)
                .register();
        schema("navigation", 1, Navigation.class, Navigation::new)
                .field("target", nullable(POINT), c -> c.target, (c, v) -> c.target = v)
                .field("faceTarget", nullable(POINT), c -> c.faceTarget, (c, v) -> c.faceTarget = v)
                .field("navigationPath", nullable(list(VECTOR2)), c -> c.navigationPath, (c, v) -> c.navigationPath = v)
                .register();
        schema("object-component", 1, ObjectComponent.class, ObjectComponent::new)
                .field("objectId", SHORT, c -> c.objectId, (c, v) -> c.objectId = v)
                .field("objectType", nullable(enumeration(AbstractRoomController.ObjectType.class)), c -> c.objectType, (c, v) -> c.objectType = v)
                .register();
        schema("object-view-state", 1, ObjectViewState.class, ObjectViewState::new)
                .field("objectId", SHORT, c -> c.objectId, (c, v) -> c.objectId = v)
                .field("state", nullable(enumeration(GameObject.State.class)), c -> c.state, (c, v) -> c.state = v)
                .field("animState", nullable(enumeration(ObjectViewState.GameObjectAnimState.class)), c -> c.animState, (c, v) -> c.animState = v)
                .field("visible", BOOLEAN, c -> c.visible, (c, v) -> c.visible = v)
                .register();
        schema("objective", 1, Objective.class, Objective::new)
                .field("objective", nullable(enumeration(Thing.HeroParty.Objective.class)), c -> c.objective, (c, v) -> c.objective = v)
                .field("objectiveTargetPlayerId", SHORT, c -> c.objectiveTargetPlayerId, (c, v) -> c.objectiveTargetPlayerId = v)
                .field("actionPointId", INT, c -> c.actionPointId, (c, v) -> c.actionPointId = v)
                .register();
        schema("owner", 1, Owner.class, Owner::new)
                .field("ownerId", SHORT, c -> c.ownerId, (c, v) -> c.ownerId = v)
                .field("controlId", SHORT, c -> c.controlId, (c, v) -> c.controlId = v)
                .register();
        schema("party", 1, Party.class, Party::new)
                .field("partyId", LONG, c -> c.partyId, (c, v) -> c.partyId = v)
                .register();
        schema("placeable", 1, Placeable.class, Placeable::new)
                .register();
        schema("player-objective", 1, PlayerObjective.class, PlayerObjective::new)
                .field("objective", nullable(enumeration(ObjectiveType.class)), c -> c.objective, (c, v) -> c.objective = v)
                .register();
        schema("portal", 1, Portal.class, Portal::new)
                .field("entrance", nullable(POINT), c -> c.entrance, (c, v) -> c.entrance = v)
                .field("lastSpawnTime", DOUBLE, c -> c.lastSpawnTime, (c, v) -> c.lastSpawnTime = v)
                .register();
        schema("portal-gem", 1, PortalGem.class, PortalGem::new)
                .register();
        schema("position", 1, Position.class, Position::new)
                .field("rotation", FLOAT, c -> c.rotation, (c, v) -> c.rotation = v)
                .field("position", nullable(VECTOR3), c -> c.position, (c, v) -> c.position = v)
                .register();
        schema("possessed", 1, Possessed.class, Possessed::new)
                .field("manaDrain", INT, c -> c.manaDrain, (c, v) -> c.manaDrain = v)
                .field("manaCheckTime", DOUBLE, c -> c.manaCheckTime, (c, v) -> c.manaCheckTime = v)
                .register();
        schema("regeneration", 1, Regeneration.class, Regeneration::new)
                .field("ownLandHealthIncrease", INT, c -> c.ownLandHealthIncrease, (c, v) -> c.ownLandHealthIncrease = v)
                .field("timeOnOwnLand", nullable(DOUBLE), c -> c.timeOnOwnLand, (c, v) -> c.timeOnOwnLand = v)
                .register();
        schema("room-component", 1, RoomComponent.class, RoomComponent::new)
                .field("roomId", SHORT, c -> c.roomId, (c, v) -> c.roomId = v)
                .field("destroyed", BOOLEAN, c -> c.destroyed, (c, v) -> c.destroyed = v)
                .field("defaultStorageType", nullable(enumeration(AbstractRoomController.ObjectType.class)), c -> c.defaultStorageType, (c, v) -> c.defaultStorageType = v)
                .field("location", nullable(POINT), c -> c.location, (c, v) -> c.location = v)
                .register();
        schema("senses", 1, Senses.class, Senses::new)
                .field("distanceCanHear", FLOAT, c -> c.distanceCanHear, (c, v) -> c.distanceCanHear = v)
                .field("distanceCanSee", FLOAT, c -> c.distanceCanSee, (c, v) -> c.distanceCanSee = v)
                .register();
        schema("slapped", 1, Slapped.class, Slapped::new)
                .field("startTime", DOUBLE, c -> c.startTime, (c, v) -> c.startTime = v)
                .register();
        schema("spellbook", 1, Spellbook.class, Spellbook::new)
                .field("type", nullable(enumeration(ResearchableType.class)), c -> c.type, (c, v) -> c.type = v)
                .field("typeId", SHORT, c -> c.typeId, (c, v) -> c.typeId = v)
                .register();
        schema("storage", 1, Storage.class, Storage::new)
                .field("room", nullable(ENTITY_ID), c -> c.room, (c, v) -> c.room = v)
                .field("objectType", nullable(enumeration(AbstractRoomController.ObjectType.class)), c -> c.objectType, (c, v) -> c.objectType = v)
                .field("currentCapacity", INT, c -> c.currentCapacity, (c, v) -> c.currentCapacity = v)
                .field("maxCapacity", INT, c -> c.maxCapacity, (c, v) -> c.maxCapacity = v)
                .register();
        schema("stored", 1, Stored.class, Stored::new)
                .field("room", nullable(ENTITY_ID), c -> c.room, (c, v) -> c.room = v)
                .field("objectType", nullable(enumeration(AbstractRoomController.ObjectType.class)), c -> c.objectType, (c, v) -> c.objectType = v)
                .register();
        schema("task-component", 1, TaskComponent.class, TaskComponent::new)
                .field("taskId", LONG, c -> c.taskId, (c, v) -> c.taskId = v)
                .field("targetEntity", nullable(ENTITY_ID), c -> c.targetEntity, (c, v) -> c.targetEntity = v)
                .field("targetLocation", nullable(POINT), c -> c.targetLocation, (c, v) -> c.targetLocation = v)
                .field("taskType", nullable(enumeration(TaskType.class)), c -> c.taskType, (c, v) -> c.taskType = v)
                .field("taskDuration", FLOAT, c -> c.taskDuration, (c, v) -> c.taskDuration = v)
                .field("taskStarted", BOOLEAN, c -> c.taskStarted, (c, v) -> c.taskStarted = v)
                .register();
        schema("threat", 1, Threat.class, Threat::new)
                .field("threat", INT, c -> c.threat, (c, v) -> c.threat = v)
                .register();
        schema("trap-component", 1, TrapComponent.class, TrapComponent::new)
                .field("trapId", SHORT, c -> c.trapId, (c, v) -> c.trapId = v)
                .register();
        schema("trap-view-state", 1, TrapViewState.class, TrapViewState::new)
                .field("trapId", SHORT, c -> c.trapId, (c, v) -> c.trapId = v)
                .field("blueprint", BOOLEAN, c -> c.blueprint, (c, v) -> c.blueprint = v)
                .register();
        schema("trigger", 1, Trigger.class, Trigger::new)
                .field("triggerId", INT, c -> c.triggerId, (c, v) -> c.triggerId = v)
                .register();
        schema("unconscious", 1, Unconscious.class, Unconscious::new)
                .field("startTime", DOUBLE, c -> c.startTime, (c, v) -> c.startTime = v)
                .register();
        schema("wooden-bridge-decay", 1, WoodenBridgeDecay.class, WoodenBridgeDecay::new)
                .field("endTime", DOUBLE, c -> c.endTime, (c, v) -> c.endTime = v)
                .register();
    }

    private ComponentSnapshots() { }
}
