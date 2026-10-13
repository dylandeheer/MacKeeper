/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import com.jme3.math.Vector3f;
import com.simsilica.es.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.LevelObjectives;
import toniarts.openkeeper.game.data.ObjectiveType;
import toniarts.openkeeper.game.save.GameEntityData;
import static org.junit.jupiter.api.Assertions.*;

class ObjectiveTargetsViewTest {
    @Test void refreshAndClicksUseOnlyAsynchronousSubscriptions() throws Exception {
        var data = new GameEntityData();
        try {
            var creature = data.createEntity();
            var component = new CreatureComponent(); component.creatureId = 4;
            data.setComponents(creature, component, new Position(0, new Vector3f(1, 0, 2)));
            var queries = new AtomicInteger();
            var queriesReleased = new AtomicInteger();
            EntityData remote = (EntityData) Proxy.newProxyInstance(EntityData.class.getClassLoader(), new Class<?>[]{EntityData.class}, (p, m, a) -> {
                if (m.getName().equals("findEntities") || m.getName().equals("getComponent") || m.getName().equals("getComponents") || m.getName().equals("watchEntity"))
                    throw new AssertionError("Synchronous remote read: " + m.getName());
                Object value = invoke(m, data, a);
                if (m.getName().equals("getEntities")) {
                    queries.incrementAndGet();
                    return countingRelease(EntitySet.class, value, queriesReleased);
                }
                return value;
            });
            var view = new ObjectiveTargetsView(remote);
            LevelObjectives.addPlayer(data, (short) 2);
            LevelObjectives.addCreature(data, creature, ObjectiveType.KILL);
            LevelObjectives.addCreature(data, creature, ObjectiveType.CONVERT);
            view.update();
            assertEquals(3, view.observations().size()); assertEquals(4, queries.get());
            assertNull(view.observations().getFirst().creatureType());
            var displayed = view.observations().get(1);
            assertEquals((short) 4, displayed.creatureType());
            assertEquals(new Vector3f(1, 0, 2), displayed.position());
            data.setComponent(creature, new Position(0, new Vector3f(8, 0, 9)));
            assertEquals(new Vector3f(1, 0, 2), displayed.position());
            view.update(); assertEquals(new Vector3f(8, 0, 9), view.observations().get(1).position());
            data.setComponent(creature, new InHand()); view.update();
            assertNull(view.observations().get(1).position());
            assertEquals((short) 4, view.observations().get(1).creatureType());
            LevelObjectives.removeCreature(data, creature); view.update(); assertEquals(0, queriesReleased.get());
            LevelObjectives.removeCreature(data, creature); view.update(); assertEquals(4, queries.get());
            view.close(); view.close(); assertEquals(4, queriesReleased.get()); assertEquals(4, queries.get());
            assertTrue(view.observations().isEmpty());
            assertThrows(IllegalStateException.class, view::update);
        } finally { data.close(); }
    }

    private static Object countingRelease(Class<?> type, Object target, AtomicInteger releases) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("release")) releases.incrementAndGet();
            return invoke(m, target, a);
        });
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException ex) { throw ex.getCause(); }
    }
}
