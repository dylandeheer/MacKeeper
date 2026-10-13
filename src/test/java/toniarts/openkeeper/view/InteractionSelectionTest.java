/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState.Type;
import static org.junit.jupiter.api.Assertions.*;

class InteractionSelectionTest {
    @Test void selectedDefinitionsMustExistWithoutRequiringAResearchCallback() {
        var map = map();
        for (var type : Type.values()) {
            int id = switch(type) {case NONE, SELL, STUFF_IN_HAND -> 0;default -> 1;};
            var saved = new InteractionSelection((short) 3, false, type, id);saved.validateDefinitions(map);
            if (id != 0) assertThrows(IllegalArgumentException.class, () -> new InteractionSelection((short)3, true, type, 2).validateDefinitions(map));
        }
        assertThrows(IllegalArgumentException.class, () -> new InteractionSelection((short)-1, true, Type.NONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new InteractionSelection((short)3, true, Type.ROOM, 0));
        assertThrows(IllegalArgumentException.class, () -> new InteractionSelection((short)3, true, Type.NONE, 1));
    }
    @Test void disabledStateCanBeStagedBeforeEngineInitializationAndCannotBeCapturedYet() throws Exception {
        var entities = new DefaultEntityData();
        var owner = new PlayerInteractionState(new Player(), map(), entities, null, null) {
            protected void onInteractionStateChange(InteractionState state) {fail("Staging enabled state must not select a tool");}
            protected void onPossession(EntityId id) {fail("Unexpected possession");}
        };
        assertDoesNotThrow(() -> owner.setEnabled(false));assertFalse(owner.isEnabled());
        var field = PlayerInteractionState.class.getDeclaredField("inputBarrier");field.setAccessible(true);
        var barrier = (InteractionInputBarrier) field.get(owner);
        assertFalse(barrier.mouse(1, false));assertFalse(barrier.key(29, true, true));
        assertDoesNotThrow(() -> owner.setEnabled(true));assertTrue(owner.isEnabled());
        assertThrows(IllegalStateException.class, owner::captureSelection);
        assertThrows(IllegalStateException.class, () -> owner.restoreSelection(new InteractionSelection((short)0, false, Type.NONE, 0)));
        entities.close();
    }
    static IKwdFile map() {
        return (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class[]{IKwdFile.class}, (p,m,a) -> {
            if (a == null || a.length != 1 || !(a[0] instanceof Integer id) || id != 1) return null;
            return switch (m.getName()) {case "getRoomById" -> new Room();case "getKeeperSpellById" -> new KeeperSpell();case "getTrapById" -> new Trap();case "getDoorById" -> new Door();default -> null;};
        });
    }
}
