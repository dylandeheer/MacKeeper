/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.component;

import com.simsilica.es.EntityComponent;
import toniarts.openkeeper.utils.Point;

/** Pending workshop production. Entity allocation order is the keeper's FIFO order. */
public final class ManufacturingOrder implements EntityComponent {
    public short playerId;
    public boolean trap;
    public short typeId;
    public int requiredWork;
    public int completedWork;
    public Point location;

    public ManufacturingOrder() { }

    public ManufacturingOrder(short playerId, boolean trap, short typeId, int requiredWork,
            int completedWork, Point location) {
        this.playerId = playerId;
        this.trap = trap;
        this.typeId = typeId;
        this.requiredWork = requiredWork;
        this.completedWork = completedWork;
        this.location = new Point(location.x, location.y);
    }
}
