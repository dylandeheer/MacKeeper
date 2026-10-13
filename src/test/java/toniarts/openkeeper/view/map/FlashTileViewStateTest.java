/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.map;

import com.jme3.app.state.AppStateManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.WorldViewState;
import toniarts.openkeeper.game.save.WorldViewSnapshots;
import static org.junit.jupiter.api.Assertions.*;

class FlashTileViewStateTest {
    @Test void repeatedRestoresRetainUnpaintedAdditionsAndDelayedSinglePulseCadence() throws Exception {
        var original = new Fixture();var restored = new Fixture();
        Point a = new Point(1,1), b = new Point(2,1);
        original.state.attach(List.of(a),true);original.states.update(.75f);original.states.update(.01f);
        assertEquals(Set.of(a),original.painted);
        original.state.attach(List.of(b),true); // Current bright phase does not paint this addition yet.
        for(int i=0;i<3;i++) {
            var saved = original.state.captureState();
            var bytes = WorldViewSnapshots.encode(new WorldViewState((short)3,4,4,saved,Map.of()));
            restored.state.restoreState(WorldViewSnapshots.decode(bytes).flash());
            assertEquals(saved,restored.state.captureState());assertEquals(Set.of(a),restored.painted);
        }
        for(float step : new float[]{.3f,.02f,2f,.1f,.1f,.1f}) {
            original.states.update(step);restored.states.update(step);
            assertEquals(original.state.captureState(),restored.state.captureState());assertEquals(original.painted,restored.painted);
        }
        original.state.attach(List.of(a),false);restored.state.attach(List.of(a),false);
        assertFalse(original.painted.contains(a));assertFalse(restored.painted.contains(a));
        assertEquals(original.state.captureState(),restored.state.captureState());
    }
    @Test void disabledAndEmptyPhasesStayFrozenAndRetiredOwnerRejectsLateRequests() throws Exception {
        var f = new Fixture();var a = new Point(0,0);
        f.state.restoreState(new WorldViewState.Flash(false,.8f,true,Set.of(a),Set.of(a)));
        var saved=f.state.captureState();f.states.update(5);assertEquals(saved,f.state.captureState());
        f.state.setEnabled(true);f.state.attach(List.of(a),false);saved=f.state.captureState();
        f.states.update(5);assertEquals(saved,f.state.captureState());assertTrue(f.painted.isEmpty());
        try(var pool=Executors.newSingleThreadExecutor()) {
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->pool.submit(f.state::captureState).get()).getCause());
        }
        f.states.detach(f.state);
        assertThrows(IllegalStateException.class,()->f.state.attach(List.of(a),true));
        assertThrows(IllegalStateException.class,()->f.state.restoreState(new WorldViewState.Flash(true,0,false,Set.of(),Set.of())));
    }
    static class Fixture {
        final Set<Point> painted=new HashSet<>();final FlashTileViewState state;final AppStateManager states;
        Fixture() throws Exception {
            var ctor=Main.class.getDeclaredConstructor();ctor.setAccessible(true);var app=ctor.newInstance();states=new AppStateManager(app);
            state=new FlashTileViewState((enabled,points)->{if(enabled)painted.addAll(points);else painted.removeAll(points);});
            states.attach(state);states.update(0);
        }
    }
}
