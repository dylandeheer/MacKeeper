/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.task;

import toniarts.openkeeper.game.controller.creature.ICreatureController;

/** Concrete task shells expose their creature edge for the graph binding phase. */
public interface CreatureTargetTask {
    TaskTarget<ICreatureController> creatureTarget();
}
