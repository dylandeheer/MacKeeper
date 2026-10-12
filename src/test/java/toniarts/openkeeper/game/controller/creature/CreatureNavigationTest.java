/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller.creature;

import com.badlogic.gdx.ai.pfa.DefaultGraphPath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.Navigation;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.component.TaskComponent;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.ITaskManager;
import toniarts.openkeeper.game.task.Task;
import static org.junit.jupiter.api.Assertions.*;

class CreatureNavigationTest {
    @Test void unchangedFailedRouteIsRetriedAtMostOncePerGameSecond() {
        Fixture f=new Fixture();
        for(int tick=0;tick<20;tick++) {
            f.time=tick/20d; f.creature.navigateToAssignedTask();
        }
        assertEquals(1,f.searches);
        f.time=1; f.creature.navigateToAssignedTask();
        assertEquals(2,f.searches);
        assertNull(f.data.getComponent(f.id,Navigation.class));
    }

    @Test void changedTaskStartAndDestinationRetryImmediately() {
        Fixture f=new Fixture();f.creature.navigateToAssignedTask();f.time=0.1;
        f.destination=new Vector2f(10,9);f.creature.navigateToAssignedTask();
        assertEquals(2,f.searches);
        f.data.setComponent(f.id,new Position(0,new Vector3f(2,0,1)));
        f.creature.navigateToAssignedTask();assertEquals(3,f.searches);
        f.task=f.newTask();f.creature.navigateToAssignedTask();assertEquals(4,f.searches);
    }

    @Test void openedRouteRecoversAndSuccessfulNavigationIsNeverThrottled() {
        Fixture f=new Fixture();f.creature.navigateToAssignedTask();
        f.reachable=true;f.time=0.5;f.creature.navigateToAssignedTask();
        assertEquals(1,f.searches);assertNull(f.data.getComponent(f.id,Navigation.class));
        f.time=1;f.creature.navigateToAssignedTask();
        assertEquals(2,f.searches);assertNotNull(f.data.getComponent(f.id,Navigation.class));
        f.data.removeComponent(f.id,Navigation.class);
        f.creature.navigateToAssignedTask();assertEquals(3,f.searches);
    }

    @Test void missingTaskOrTargetIsSafeAndClearsTheFailure() {
        Fixture f=new Fixture();f.creature.navigateToAssignedTask();
        f.destination=null;assertDoesNotThrow(f.creature::navigateToAssignedTask);
        f.destination=new Vector2f(9,9);f.creature.navigateToAssignedTask();
        assertEquals(2,f.searches);
        f.task=null;assertDoesNotThrow(f.creature::navigateToAssignedTask);
        assertEquals(2,f.searches);
    }

    @Test void rewoundGameClockDoesNotStrandTheCreature() {
        Fixture f=new Fixture();f.time=50;f.creature.navigateToAssignedTask();
        f.time=0;f.creature.navigateToAssignedTask();assertEquals(2,f.searches);
    }

    private static final class Fixture {
        final DefaultEntityData data=new DefaultEntityData();
        final EntityId id=data.createEntity();
        double time;
        int searches;
        boolean reachable;
        Vector2f destination=new Vector2f(9,9);
        Task task=newTask();
        final CreatureController creature;
        Fixture() {
            data.setComponents(id,new Position(0,new Vector3f(1,0,1)),new TaskComponent());
            INavigationService navigation=proxy(INavigationService.class,(name,args)-> {
                if(!name.equals("findPath"))throw new AssertionError(name);
                searches++;
                return reachable?new DefaultGraphPath<>():null;
            });
            ITaskManager tasks=proxy(ITaskManager.class,(name,args)-> {
                if(!name.equals("getTaskById"))throw new AssertionError(name);
                return task;
            });
            creature=new CreatureController(id,data,null,navigation,tasks,()->time,Map.of(),
                    null,null,null,null,null,null);
        }
        Task newTask() {
            return proxy(Task.class,(name,args)->switch(name) {
                case "getTarget"->destination;
                case "isFaceTarget"->false;
                default->throw new AssertionError(name);
            });
        }
    }

    private static <T> T proxy(Class<T> type,BiFunction<String,Object[],Object> call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
                (instance,method,args)->call.apply(method.getName(),args)));
    }
}
