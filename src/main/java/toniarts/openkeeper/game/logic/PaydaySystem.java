/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.logic;

import com.simsilica.es.*;
import java.util.Map;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.GameWorldController;
import toniarts.openkeeper.game.controller.IGameTimer;
import toniarts.openkeeper.tools.convert.map.*;

/** Authoritative game-time wage cycle. Collection is performed by a separate room task. */
public final class PaydaySystem implements IGameLogicUpdatable {
    private final EntityData data;
    private final EntitySet creatures;
    private final IKwdFile kwd;
    private final IGameTimer timer;
    private final double first, frequency, cutoff;
    private long cycle;

    public PaydaySystem(EntityData data, IKwdFile kwd, IGameTimer timer,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings) {
        this.data = data;
        this.kwd = kwd;
        this.timer = timer;
        first = value(settings, Variable.MiscVariable.MiscType.TIME_BEFORE_FIRST_PAY_DAY_SECONDS);
        frequency = value(settings, Variable.MiscVariable.MiscType.PAY_DAY_FREQUENCY_SECONDS);
        cutoff = value(settings, Variable.MiscVariable.MiscType.PAY_DAY_CUT_OFF_TIME_SECONDS);
        if (first < 0 || frequency <= 0 || cutoff < 0) throw new IllegalArgumentException("Invalid payday timing");
        creatures = data.getEntities(CreatureComponent.class, Owner.class, Health.class);
    }

    public record State(long cycle) {
        public State {
            if (cycle < 0) throw new IllegalArgumentException("Negative payday cycle");
        }
    }

    public State captureState() { return new State(cycle); }

    /** Bind saved cycle without charging salary or clearing anger on startup. */
    public static PaydaySystem restoreState(EntityData data, IKwdFile kwd, IGameTimer timer,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings, State state) {
        java.util.Objects.requireNonNull(state);
        PaydaySystem result = new PaydaySystem(data, kwd, timer, settings);
        double now = timer.getGameTime();
        if (!Double.isFinite(now) || now < 0 || state.cycle() > result.reachedCycle(now)) {
            result.stop();
            throw new IllegalArgumentException("Payday cycle exceeds saved game time");
        }
        result.cycle = state.cycle();
        return result;
    }

    private long reachedCycle(double now) {
        return now < first ? 0 : 1 + (long) Math.floor((now - first) / frequency);
    }

    private static double value(Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings, Variable.MiscVariable.MiscType type) {
        var variable = settings.get(type);
        if (variable == null || !Float.isFinite(variable.getValue())) throw new IllegalArgumentException("Missing/invalid " + type);
        return variable.getValue();
    }

    public long getCycle() { return cycle; }

    /** Gross next-cycle wages less gifts already credited as advance payment. */
    public static long estimateNextPayroll(EntityData data, short player) {
        long total = 0;
        for (EntityId id : data.findEntities(null, CreatureComponent.class, Owner.class, Health.class)) {
            Owner owner = data.getComponent(id, Owner.class);
            if (owner == null || owner.ownerId != player || !eligible(data, id)) continue;
            CreatureComponent creature = data.getComponent(id, CreatureComponent.class);
            if (creature == null) continue;
            CreatureSalary salary = salary(data, id, player);
            total += Math.max(0, creature.pay - salary.advance);
        }
        return total;
    }

    public static boolean eligible(EntityData data, EntityId id) {
        Owner owner = data.getComponent(id, Owner.class);
        Health health = data.getComponent(id, Health.class);
        CreatureComponent creature = data.getComponent(id, CreatureComponent.class);
        return owner != null && owner.ownerId >= Player.KEEPER1_ID && owner.controlId == owner.ownerId
                && creature != null && creature.pay > 0 && health != null && health.health > 0
                && data.getComponent(id, Death.class) == null
                && data.getComponent(id, CreatureImprisoned.class) == null
                && data.getComponent(id, CreatureTortured.class) == null;
    }

    /** Caller holds the world gold lock when this can race with hand/room transactions. */
    public static CreatureSalary salary(EntityData data, EntityId id, short owner) {
        CreatureSalary salary = data.getComponent(id, CreatureSalary.class);
        return salary == null || salary.employer != owner ? new CreatureSalary(owner, 0, 0, 0) : salary;
    }

    @Override public void processTick(float tpf) {
        synchronized (GameWorldController.GOLD_LOCK) {
            creatures.applyChanges();
            double now = timer.getGameTime();
            long reached = reachedCycle(now);
            long elapsed = Math.max(0, reached - cycle);
            double dueAt = first + cycle * frequency;
            for (Entity entity : creatures) {
                EntityId id = entity.getId();
                short owner = entity.get(Owner.class).ownerId;
                CreatureSalary old = data.getComponent(id, CreatureSalary.class);
                CreatureSalary salary = salary(data, id, owner);
                if (old != null && old.employer != owner) {
                    data.setComponent(id, salary);
                    CreatureMood previousMood = data.getComponent(id, CreatureMood.class);
                    if (previousMood != null) data.setComponent(id, previousMood.clear(CreatureMood.REASON_NO_PAY));
                }
                if (!eligible(data, id)) continue;
                if (elapsed > 0) {
                    int wage = entity.get(CreatureComponent.class).pay;
                    long coveredCycles = Math.min(elapsed, salary.advance / wage);
                    salary = salary.charge(Math.multiplyExact(elapsed, wage), dueAt + coveredCycles * frequency);
                }
                if (salary != old) data.setComponent(id, salary);
                CreatureMood mood = data.getComponent(id, CreatureMood.class);
                if (mood != null && salary.owed > 0 && now >= salary.unpaidSince + cutoff) {
                    int anger = CreatureMood.toRuntimeValue(kwd.getCreature(entity.get(CreatureComponent.class).creatureId).getAttributes().getAngerNoPay());
                    if (anger > 0) data.setComponent(id, mood.add(CreatureMood.REASON_NO_PAY, anger));
                }
            }
            cycle = Math.max(cycle, reached);
        }
    }

    @Override public void start() { }
    @Override public void stop() { creatures.release(); }
}
