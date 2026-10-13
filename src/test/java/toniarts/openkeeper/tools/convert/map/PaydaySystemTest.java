/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.logic.PaydaySystem;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.trigger.*;
import static org.junit.jupiter.api.Assertions.*;

class PaydaySystemTest {
    @Test void cyclesUseMapTimeAndDoNotRepeatAtTheSameTime() {
        Fixture f = new Fixture(1200);
        f.tick(1199); assertEquals(0, f.salary().owed);
        f.tick(1200); assertEquals(500, f.salary().owed); assertEquals(1,f.system.getCycle());
        f.tick(1200); assertEquals(500, f.salary().owed);
        f.tick(3600); assertEquals(1500, f.salary().owed); assertEquals(3,f.system.getCycle());
        assertEquals(73,f.data.getComponent(f.id,Gold.class).gold); // loot is neither wages nor advance
    }
    @Test void advancePaysExactlyOnceAndLeavesOnlyTheUncoveredDebt() {
        Fixture f = new Fixture(600);
        f.data.setComponent(f.id, new CreatureSalary((short)3,0,750,0));
        f.tick(600); assertEquals(0,f.salary().owed); assertEquals(250,f.salary().advance);
        f.tick(1200); assertEquals(250,f.salary().owed); assertEquals(0,f.salary().advance);
        assertEquals(1200,f.salary().unpaidSince);
        f.data.setComponent(f.id, f.salary().paid(100));
        assertEquals(150,f.salary().owed); assertEquals(1200,f.salary().unpaidSince);
        f.data.setComponent(f.id, f.salary().paid(200));
        assertEquals(0,f.salary().owed); assertEquals(50,f.salary().advance);
    }
    @Test void catchupStartsUnpaidWindowAtTheFirstUncoveredCycle() {
        Fixture f=new Fixture(600);
        f.data.setComponent(f.id,new CreatureSalary((short)3,0,1000,0));
        f.tick(1800);
        assertEquals(500,f.salary().owed);
        assertEquals(1800,f.salary().unpaidSince);
        assertEquals(0,f.data.getComponent(f.id,CreatureMood.class).noPay);
    }
    @Test void nextPayrollEstimateExcludesLootAndAccountsForAdvance() {
        Fixture f=new Fixture(600);
        assertEquals(500,PaydaySystem.estimateNextPayroll(f.data,(short)3));
        f.data.setComponent(f.id,new CreatureSalary((short)3,100,300,600));
        assertEquals(200,PaydaySystem.estimateNextPayroll(f.data,(short)3));
        assertEquals(0,PaydaySystem.estimateNextPayroll(f.data,(short)4));
    }
    @Test void unpaidAngerStartsAfterCutoffAndPreservesOtherReasons() {
        Fixture f = new Fixture(600);
        f.tick(600); f.tick(719); assertEquals(0,f.data.getComponent(f.id,CreatureMood.class).noPay);
        f.tick(720); assertEquals(15,f.data.getComponent(f.id,CreatureMood.class).noPay);
        assertEquals(200,f.data.getComponent(f.id,CreatureMood.class).noLair);
    }
    @Test void prisonersAndZeroWageCreaturesDoNotAccrueNewWages() {
        Fixture f = new Fixture(600);
        f.data.setComponent(f.id,new CreatureImprisoned(0,0));
        f.tick(600); assertNull(f.salary());
        f.data.removeComponent(f.id,CreatureImprisoned.class);
        f.tick(601); assertEquals(0,f.salary().owed);
        f.tick(1200); assertEquals(500,f.salary().owed);
        CreatureComponent c=new CreatureComponent(f.data.getComponent(f.id,CreatureComponent.class));c.pay=0;
        f.data.setComponent(f.id,c);f.tick(1800);assertEquals(500,f.salary().owed);
    }
    @Test void conversionDoesNotChargeTheNewEmployerForPreviousDebt() {
        Fixture f=new Fixture(600);f.tick(720);assertEquals(500,f.salary().owed);
        f.data.setComponent(f.id,new Owner((short)4,(short)4)); f.tick(721);
        assertEquals(4,f.salary().employer);assertEquals(0,f.salary().owed);
        assertEquals(0,f.data.getComponent(f.id,CreatureMood.class).noPay);
        assertEquals(200,f.data.getComponent(f.id,CreatureMood.class).noLair);
    }
    @Test void paidLedgerRejectsNegativeAmountsAndUsesWideArithmetic() {
        CreatureSalary salary=new CreatureSalary((short)3,0,0,0).charge((long)Integer.MAX_VALUE+1,600);
        assertEquals(2147483648L,salary.owed);
        assertEquals(1,salary.paid(Integer.MAX_VALUE).owed);
        assertThrows(IllegalArgumentException.class,()->salary.paid(-1));
        assertThrows(IllegalArgumentException.class,()->salary.charge(-1,0));
    }
    @Test void triggerSeesEachNewCycleWithoutReplayingOneBeforeItsCreation() {
        long[] cycle={3};
        TriggerGeneric condition=new TriggerGeneric(null);condition.setId(1);condition.setType(TriggerGeneric.TargetType.LEVEL_PAY_DAY);
        IKwdFile kwd=proxy(IKwdFile.class,(name,args)-> { if(name.equals("getTriggers"))return Map.of(1,condition);throw new AssertionError(name); });
        ILevelInfo info=proxy(ILevelInfo.class,(name,args)->switch(name){case "getLevelData"->kwd;case "getPaydayCycle"->cycle[0];default->throw new AssertionError(name);});
        class Control extends TriggerControl {
            Control(){super(null,info,()->0,null,null,1);}
            boolean check(){return isActive((TriggerGenericData)root.getChild(0));}
        }
        Control control=new Control();assertFalse(control.check());cycle[0]=4;
        assertTrue(control.check());assertFalse(control.check());cycle[0]=5;assertTrue(control.check());
    }
    static class Fixture {
        final DefaultEntityData data=new DefaultEntityData();final EntityId id=data.createEntity();
        final PaydaySystem system;double now;
        Fixture(int interval){
            Creature raw=new Creature();raw.getAttributes().setAngerNoPay((short)60);
            IKwdFile kwd=proxy(IKwdFile.class,(n,a)-> {if(n.equals("getCreature"))return raw;throw new AssertionError(n);});
            CreatureComponent c=new CreatureComponent();c.creatureId=7;c.pay=500;
            data.setComponents(id,c,new Owner((short)3,(short)3),new Health(100,100),new Gold(73,5000),new CreatureMood(0,0,200,0,0,0));
            var settings=new EnumMap<Variable.MiscVariable.MiscType,Variable.MiscVariable>(Variable.MiscVariable.MiscType.class);
            put(settings,Variable.MiscVariable.MiscType.TIME_BEFORE_FIRST_PAY_DAY_SECONDS,interval);
            put(settings,Variable.MiscVariable.MiscType.PAY_DAY_FREQUENCY_SECONDS,interval);
            put(settings,Variable.MiscVariable.MiscType.PAY_DAY_CUT_OFF_TIME_SECONDS,120);
            system=new PaydaySystem(data,kwd,()->now,settings);
        }
        void tick(double time){now=time;system.processTick(.05f);}
        CreatureSalary salary(){return data.getComponent(id,CreatureSalary.class);}
    }
    static void put(Map<Variable.MiscVariable.MiscType,Variable.MiscVariable> settings,Variable.MiscVariable.MiscType type,int value){var v=new Variable.MiscVariable();v.setValue(value);settings.put(type,v);}
    interface Call{Object invoke(String name,Object[] args);}
    static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
}
