/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.component;

import com.simsilica.es.EntityComponent;

/** Last applied damaging player's identity, retained through incapacitation until death. */
public final class LastDamageSource implements EntityComponent {
    public short playerId;
    public LastDamageSource() { }
    public LastDamageSource(short playerId) { this.playerId = playerId; }
}
