/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.listener;

/** Server simulation event, emitted at zero wall health, before terrain conversion. */
@FunctionalInterface
public interface DungeonBreachListener {
    void onDungeonBreached(short defenderId, short attackerId);
}
