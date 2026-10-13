/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import com.simsilica.es.EntityId;
import java.util.Map;
import java.util.Objects;
import toniarts.openkeeper.game.task.TaskIdentityAllocator;

/** Common task identity and per-creature execution progress. */
public record TaskBaseState(TaskIdentityAllocator.Identity identity, Map<EntityId, Float> assignees) {
    public TaskBaseState {
        Objects.requireNonNull(identity);assignees = Map.copyOf(assignees);
        if (assignees.size() > 65_536) throw new IllegalArgumentException("Too many task assignees");
        assignees.forEach((id, duration) -> {
            if (id.getId() < 0 || !Float.isFinite(duration) || duration < 0)
                throw new IllegalArgumentException("Invalid task assignment");
        });
    }
}
