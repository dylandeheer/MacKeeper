/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.trigger;

import java.util.List;
import toniarts.openkeeper.game.save.TriggerSnapshots;

/** Ordered execution-tree capture; controller ownership/binding is a separate section. */
public interface TriggerStateSource {
    List<TriggerSnapshots.State> captureTrees();
}
