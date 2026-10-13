/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller.creature;

/** The pending result of an idle creature's work search, with no captured callback or live owner. */
public enum WorkContinuation {
    IDLE_ENTER, IDLE_UPDATE;

    public void complete(ICreatureController creature, boolean assigned) {
        if (assigned || (creature.isWorker() && creature.isTooMuchGold() && creature.dropGoldToTreasury())) {
            creature.getStateMachine().changeState(CreatureState.WORK);
        } else if (this == IDLE_ENTER || creature.isStopped()) {
            creature.navigateToRandomPoint();
        }
    }
}
