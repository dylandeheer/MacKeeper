/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.component;

import com.simsilica.es.EntityComponent;

/** Salary ledger, separate from loot in the creature's purse. Amounts are gold units. */
public final class CreatureSalary implements EntityComponent {
    public short employer;
    public long owed;
    public long advance;
    public double unpaidSince;

    public CreatureSalary() { }

    public CreatureSalary(short employer, long owed, long advance, double unpaidSince) {
        this.employer = employer;
        this.owed = owed;
        this.advance = advance;
        this.unpaidSince = unpaidSince;
    }

    public CreatureSalary charge(long wage, double now) {
        if (wage < 0) throw new IllegalArgumentException("Negative wage");
        long covered = Math.min(wage, advance);
        long due = wage - covered;
        return new CreatureSalary(employer, Math.addExact(owed, due), advance - covered,
                owed == 0 && due > 0 ? now : unpaidSince);
    }

    public CreatureSalary paid(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative payment");
        long settled = Math.min(owed, amount);
        long remaining = owed - settled;
        return new CreatureSalary(employer, remaining, Math.addExact(advance, amount - settled),
                remaining == 0 ? 0 : unpaidSince);
    }
}
