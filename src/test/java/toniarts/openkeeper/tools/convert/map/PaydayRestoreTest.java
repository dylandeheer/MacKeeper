/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.logic.PaydaySystem;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

class PaydayRestoreTest {
    @Test void repeatedRestoresDoNotRechargePastWagesAndMatchUninterruptedFutureTicks() throws Exception {
        for (long advance : new long[]{0, 750, 10_000}) {
            var original = new GameEntityData();EntityId id = original.createEntity();
            var creature = new CreatureComponent();creature.creatureId = 7;creature.pay = 500;
            original.setComponents(id, creature, new Owner((short) 3, (short) 3), new Health(100, 100),
                    new CreatureSalary((short) 3, 0, advance, 0), new CreatureMood(0, 0, 200, 0, 0, 0));
            double[] now = {0};var settings = settings();var definition = definition();
            var system = new PaydaySystem(original, definition, () -> now[0], settings);
            try {
                for (double captureTime : new double[]{599, 600, 1320, 1800, 2419}) {
                    now[0] = captureTime;system.processTick(.05f);
                    byte[] before = EntitySnapshots.encode(original.capture());
                    var staged = GameEntityData.restore(EntitySnapshots.decode(before));
                    double[] resumedTime = {captureTime};
                    var resumed = PaydaySystem.restoreState(staged, definition, () -> resumedTime[0], settings, system.captureState());
                    try {
                        resumed.start();assertArrayEquals(before, EntitySnapshots.encode(staged.capture()));
                        byte[] ledger = ComponentSnapshots.encode(ComponentSnapshots.capture(original.getComponent(id, CreatureSalary.class)));
                        resumed.processTick(.05f);system.processTick(.05f);
                        assertArrayEquals(ledger, ComponentSnapshots.encode(ComponentSnapshots.capture(staged.getComponent(id, CreatureSalary.class))));
                        // Unpaid mood accrues on each ordinary update, even at
                        // the same clock value. Compare equal update counts.
                        assertArrayEquals(EntitySnapshots.encode(original.capture()), EntitySnapshots.encode(staged.capture()));
                        // Advance both independent worlds to the next authored cycle.
                        now[0] = resumedTime[0] = (Math.floor(captureTime / 600) + 1) * 600;
                        system.processTick(.05f);resumed.processTick(.05f);
                        assertEquals(system.captureState(), resumed.captureState());
                        assertArrayEquals(EntitySnapshots.encode(original.capture()), EntitySnapshots.encode(staged.capture()));
                    } finally {resumed.stop();staged.close();}
                }
            } finally {system.stop();original.close();}
        }
    }

    @Test void futureCyclesAndInvalidClocksAreRejectedWithoutWritingSalary() throws Exception {
        var data = new GameEntityData();
        try {
            byte[] empty = EntitySnapshots.encode(data.capture());
            assertThrows(IllegalArgumentException.class, () -> new PaydaySystem.State(-1));
            for (double clock : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 599}) {
                assertThrows(IllegalArgumentException.class, () -> PaydaySystem.restoreState(data, definition(), () -> clock, settings(), new PaydaySystem.State(1)));
                assertArrayEquals(empty, EntitySnapshots.encode(data.capture()));
            }
        } finally {data.close();}
    }

    static Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings() {
        var settings = new EnumMap<Variable.MiscVariable.MiscType, Variable.MiscVariable>(Variable.MiscVariable.MiscType.class);
        PaydaySystemTest.put(settings, Variable.MiscVariable.MiscType.TIME_BEFORE_FIRST_PAY_DAY_SECONDS, 600);
        PaydaySystemTest.put(settings, Variable.MiscVariable.MiscType.PAY_DAY_FREQUENCY_SECONDS, 600);
        PaydaySystemTest.put(settings, Variable.MiscVariable.MiscType.PAY_DAY_CUT_OFF_TIME_SECONDS, 120);
        return settings;
    }
    static IKwdFile definition() {
        var creature = new Creature();creature.getAttributes().setAngerNoPay((short) 60);
        return PaydaySystemTest.proxy(IKwdFile.class, (name, args) -> {
            if (name.equals("getCreature")) return creature;throw new AssertionError(name);
        });
    }
}
