/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.badlogic.gdx.math.Vector2;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.controller.chicken.ChickenState;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class ComponentSnapshotsTest {
    @Test void registryCoversEveryConcreteComponentAndEveryInheritedOrPrivateField() throws Exception {
        Set<Class<?>> actual = new HashSet<>();
        // Inspect the source inventory independently of the registry. A new
        // component or field must fail this test until persistence covers it.
        try (var files = Files.list(Path.of("src/main/java/toniarts/openkeeper/game/component"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName("toniarts.openkeeper.game.component." + name);
                if (EntityComponent.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())) actual.add(type);
            }
        }
        assertEquals(actual, ComponentSnapshots.registeredTypes());
        for (Class<?> type : actual) {
            Set<String> fields = new HashSet<>();for (Field field : fields(type)) fields.add(field.getName());
            assertEquals(fields, ComponentSnapshots.fieldNames(type), type.getName());
        }
    }

    @Test void everyComponentRoundTripsEveryFieldIncludingNullsAndPrivateAiStates() throws Exception {
        for (Class<? extends EntityComponent> type : ComponentSnapshots.registeredTypes()) {
            for (boolean populate : new boolean[]{false, true}) {
                EntityComponent original = type.getConstructor().newInstance();
                if (populate) {
                    int index = 0;
                    for (Field field : fields(type)) field.set(original, sample(field, ++index));
                }
                var snapshot = ComponentSnapshots.capture(original);
                byte[] encoded = ComponentSnapshots.encode(snapshot);
                var decoded = ComponentSnapshots.decode(encoded);
                EntityComponent restored = ComponentSnapshots.restore(decoded);
                assertEquals(type, restored.getClass());assertNotSame(original, restored);
                for (Field field : fields(type)) assertEquals(field.get(original), field.get(restored), type.getSimpleName()+"."+field.getName());
                assertArrayEquals(encoded, ComponentSnapshots.encode(ComponentSnapshots.capture(restored)));
                assertEquals(snapshot.referencedEntityIds(), decoded.referencedEntityIds());
            }
        }
    }

    @Test void snapshotsAndEveryRestoredCopyOwnTheirMutableValues() throws Exception {
        Navigation navigation = new Navigation();navigation.target = new Point(2, 4);
        navigation.faceTarget = new Point(6, 8);navigation.navigationPath = new ArrayList<>(List.of(new Vector2(1, 3)));
        var captured = ComponentSnapshots.capture(navigation);byte[] before = ComponentSnapshots.encode(captured);
        navigation.target = new Point(20, 4);navigation.navigationPath.getFirst().x = 99;navigation.navigationPath.clear();
        var restored = (Navigation) ComponentSnapshots.restore(captured);
        assertEquals(new Point(2, 4), restored.target);assertEquals(List.of(new Vector2(1, 3)), restored.navigationPath);
        restored.target = new Point(2, 60);restored.navigationPath.getFirst().y = 77;restored.navigationPath.add(new Vector2());
        assertArrayEquals(before, ComponentSnapshots.encode(captured));
        assertEquals(List.of(new Vector2(1, 3)), ((Navigation)ComponentSnapshots.restore(captured)).navigationPath);

        MapTile tile = new MapTile();tile.selection = new HashMap<>(Map.of((short)3, true));
        tile.flashing = new HashMap<>();tile.p = new Point(1, 2);tile.room = new EntityId(123);
        var tileSnapshot = ComponentSnapshots.capture(tile);tile.selection.clear();tile.p = new Point(42, 2);
        MapTile first = (MapTile)ComponentSnapshots.restore(tileSnapshot);first.selection.put((short)4, false);
        MapTile second = (MapTile)ComponentSnapshots.restore(tileSnapshot);
        assertEquals(Map.of((short)3, true), second.selection);assertNotNull(second.flashing);assertEquals(new Point(1, 2), second.p);
        assertEquals(Set.of(new EntityId(123)), tileSnapshot.referencedEntityIds());

        CreatureSpells spells = new CreatureSpells(new ArrayList<>(List.of(new EntityId(5), new EntityId(7))));
        var spellSnapshot = ComponentSnapshots.capture(spells);spells.creatureSpells.clear();
        ((CreatureSpells)ComponentSnapshots.restore(spellSnapshot)).creatureSpells.clear();
        assertEquals(Set.of(new EntityId(5), new EntityId(7)), spellSnapshot.referencedEntityIds());
        assertEquals(2, ((CreatureSpells)ComponentSnapshots.restore(spellSnapshot)).creatureSpells.size());
    }

    @Test void wireFixtureUsesStableTypeVersionAndEnumNamesRatherThanOrdinals() throws Exception {
        byte[] fixture = record("creature-ai", 1, out -> {
            out.writeDouble(12.5);text(out, "IDLE");out.writeShort(4);
        });
        CreatureAi ai = (CreatureAi)ComponentSnapshots.restore(ComponentSnapshots.decode(fixture));
        assertEquals(12.5, ai.stateStartTime);assertEquals(CreatureState.IDLE, ai.getCreatureState());assertEquals(4, ai.creatureId);
        assertArrayEquals(fixture, ComponentSnapshots.encode(ComponentSnapshots.capture(ai)));
        for (CreatureState state : CreatureState.values()) {
            var restored = (CreatureAi)ComponentSnapshots.restore(ComponentSnapshots.decode(ComponentSnapshots.encode(
                    ComponentSnapshots.capture(new CreatureAi(1, state, (short)3)))));
            assertEquals(state, restored.getCreatureState());
        }
        for (ChickenState state : ChickenState.values()) {
            var restored = (ChickenAi)ComponentSnapshots.restore(ComponentSnapshots.decode(ComponentSnapshots.encode(
                    ComponentSnapshots.capture(new ChickenAi(2, state)))));
            assertEquals(state, restored.getChickenState());
        }
        for (var state : toniarts.openkeeper.tools.convert.map.Creature.AttackType.values()) {
            var attack = new CreatureMeleeAttack(state.ordinal(), 50, 2, 3);
            byte[] bytes = ComponentSnapshots.encode(ComponentSnapshots.capture(attack));
            var restored = (CreatureMeleeAttack)ComponentSnapshots.restore(ComponentSnapshots.decode(bytes));
            assertEquals(state, restored.getAttackType());
            // Nullable inherited time, recharge and range precede the name.
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                in.readInt();in.readInt();in.skipNBytes(in.readInt());in.readInt();
                assertFalse(in.readBoolean());assertEquals(2f,in.readFloat());assertEquals(3f,in.readFloat());
                assertEquals(state.name(),new String(in.readNBytes(in.readInt()),StandardCharsets.UTF_8));
            }
        }
    }

    @Test void inheritedNullableAttackTimesAndWideBalancesSurvive() throws Exception {
        CreatureSpell spell = new CreatureSpell((short)5, new EntityId(999), 1.5f, 4.25f);
        for (Double start : new Double[]{null, 1_234_567.125}) {
            spell.attactStartTime = start;
            var restored = (CreatureSpell)ComponentSnapshots.restore(ComponentSnapshots.decode(ComponentSnapshots.encode(ComponentSnapshots.capture(spell))));
            assertEquals(start, restored.attactStartTime);assertEquals(1.5f, restored.rechargeTime);assertEquals(4.25f, restored.range);
        }
        long wide = 9_007_199_254_740_993L;
        var restored = (CreatureSalary)ComponentSnapshots.restore(ComponentSnapshots.decode(ComponentSnapshots.encode(
                ComponentSnapshots.capture(new CreatureSalary((short)3, wide, wide+1, 123.5)))));
        assertEquals(wide, restored.owed);assertEquals(wide+1, restored.advance);
    }

    @Test void flagEncodingIsDeterministicAndRetainsFalseNullAndEmptyValues() throws Exception {
        MapTile a = new MapTile(), b = new MapTile();a.selection = new LinkedHashMap<>();b.selection = new LinkedHashMap<>();
        a.selection.put((short)5, false);a.selection.put((short)3, true);
        b.selection.put((short)3, true);b.selection.put((short)5, false);
        assertArrayEquals(ComponentSnapshots.encode(ComponentSnapshots.capture(a)), ComponentSnapshots.encode(ComponentSnapshots.capture(b)));
        assertNull(((MapTile)ComponentSnapshots.restore(ComponentSnapshots.capture(a))).flashing);
        a.flashing = new HashMap<>();assertNotNull(((MapTile)ComponentSnapshots.restore(ComponentSnapshots.capture(a))).flashing);
    }

    @Test void rejectsTruncationTrailingDataUnknownTypesAndVersions() throws Exception {
        byte[] valid = ComponentSnapshots.encode(ComponentSnapshots.capture(new CreatureAi(42, CreatureState.IDLE, (short)1)));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);assertThrows(IOException.class, () -> ComponentSnapshots.decode(truncated));
        }
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(Arrays.copyOf(valid, valid.length+1)));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("java.lang.Runtime", 1, out -> {})));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("creature-ai", 2, out -> {})));
        byte[] version = valid.clone();version[7] = 2;assertThrows(IOException.class, () -> ComponentSnapshots.decode(version));
        byte[] magic = valid.clone();magic[0] = 0;assertThrows(IOException.class, () -> ComponentSnapshots.decode(magic));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(new byte[ComponentSnapshots.MAX_COMPONENT_BYTES+1]));
        assertThrows(IOException.class, () -> ComponentSnapshots.capture(new EntityComponent(){}));
    }

    @Test void rejectsInvalidNumericEnumBooleanReferenceAndLengthPayloads() throws Exception {
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("creature-ai", 1, out -> {
            out.writeDouble(Double.NaN);text(out,"IDLE");out.writeShort(1);
        })));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("creature-ai", 1, out -> {
            out.writeDouble(1);text(out,"NOT_A_STATE");out.writeShort(1);
        })));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("door-component", 1, out -> {out.writeShort(1);out.writeByte(2);out.writeBoolean(false);})));
        var zero = (AttackTarget)ComponentSnapshots.restore(ComponentSnapshots.decode(record("attack-target", 1, out -> {out.writeBoolean(true);out.writeLong(0);})));
        assertEquals(new EntityId(0), zero.entityId);
        for (long invalid : new long[]{-1, Long.MIN_VALUE}) assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("attack-target", 1, out -> {out.writeBoolean(true);out.writeLong(invalid);})));
        for (int size : new int[]{-1, Integer.MAX_VALUE, SaveValues.MAX_LIST_ITEMS+1}) {
            assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("creature-spells", 1, out -> {out.writeBoolean(true);out.writeInt(size);})));
        }
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("map-tile", 1, out -> {
            out.writeBoolean(true);out.writeInt(2);out.writeShort(3);out.writeBoolean(true);out.writeShort(3);out.writeBoolean(false);
        })));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("map-tile", 1, out -> {out.writeBoolean(true);out.writeInt(257);})));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("creature-component", 1, out -> {
            out.writeBoolean(true);out.writeInt(2);out.writeByte(0xc3);out.writeByte(0x28);
        })));
        assertThrows(IOException.class, () -> ComponentSnapshots.decode(record("creature-component", 1, out -> {out.writeBoolean(true);out.writeInt(Integer.MAX_VALUE);})));
    }

    @Test void invalidLiveStateCannotBeCapturedAndUtf8RoundTripsWithoutLoss() throws Exception {
        Position p = new Position();p.position = new Vector3f(Float.POSITIVE_INFINITY, 0, 0);
        assertThrows(IOException.class, () -> ComponentSnapshots.capture(p));
        assertThrows(IOException.class, () -> ComponentSnapshots.capture(new CreatureSpells(Arrays.asList(new EntityId(1), null))));
        MapTile tile = new MapTile();tile.selection = new HashMap<>();tile.selection.put((short)1, null);
        assertThrows(IOException.class, () -> ComponentSnapshots.capture(tile));
        CreatureComponent c = new CreatureComponent();c.name = "Imp 🦇 – 地下世界";
        assertEquals(c.name, ((CreatureComponent)ComponentSnapshots.restore(ComponentSnapshots.decode(ComponentSnapshots.encode(ComponentSnapshots.capture(c))))).name);
        c.name = "\ud800";assertThrows(IOException.class, () -> ComponentSnapshots.capture(c));
        c.name = "x".repeat(SaveValues.MAX_STRING_BYTES+1);assertThrows(IOException.class, () -> ComponentSnapshots.capture(c));
        c.name = "🦇".repeat(SaveValues.MAX_STRING_BYTES/3);assertThrows(IOException.class, () -> ComponentSnapshots.capture(c));
        Navigation nav = new Navigation();nav.navigationPath = Collections.nCopies(SaveValues.MAX_LIST_ITEMS+1, new Vector2());
        assertThrows(IOException.class, () -> ComponentSnapshots.capture(nav));
    }

    static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {field.setAccessible(true);fields.add(field);}
        }
        return fields;
    }
    static Object sample(Field field, int index) {
        Class<?> t = field.getType();
        if (Set.of("creatureState", "chickenState", "attackType").contains(field.getName())) return 1;
        if (t == int.class) return index+10;if (t == long.class) return 9_007_199_254_740_993L+index;
        if (t == short.class) return (short)(index+1);if (t == boolean.class) return true;
        if (t == float.class) return index+.125f;if (t == double.class || t == Double.class) return index+.25;
        if (t == String.class) return "Name 🦇 "+index;if (t == EntityId.class) return new EntityId(index+200);
        if (t == Point.class) return new Point(index, index+1);if (t == Vector3f.class) return new Vector3f(index, index+1, index+2);
        if (t.isEnum()) return t.getEnumConstants()[t.getEnumConstants().length-1];
        if (t == Map.class) return new HashMap<>(Map.of((short)3,true,(short)5,false));
        if (t == List.class) return field.getName().equals("creatureSpells") ? new ArrayList<>(List.of(new EntityId(400),new EntityId(500))) : new ArrayList<>(List.of(new Vector2(1,2),new Vector2(3,4)));
        throw new AssertionError("Missing independent fixture value for "+field);
    }
    @FunctionalInterface interface Payload {void write(DataOutputStream out) throws IOException;}
    static byte[] record(String type, int version, Payload payload) throws IOException {
        var bytes = new ByteArrayOutputStream();try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b4353);out.writeInt(1);text(out,type);out.writeInt(version);payload.write(out);
        }return bytes.toByteArray();
    }
    static void text(DataOutputStream out, String text) throws IOException {byte[] b=text.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
}
