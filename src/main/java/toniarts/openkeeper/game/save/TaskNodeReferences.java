/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.util.*;
import toniarts.openkeeper.game.task.Task;

/** Snapshot-local object identity. Objective decorators can share a runtime task ID. */
public final class TaskNodeReferences {
    public record Reference(long nodeId, long taskId) {
        public Reference {
            if (nodeId < 0 || taskId < 0) throw new IllegalArgumentException("Invalid task node reference");
        }
    }
    /** One index shared by all task graph and controller captures within a simulation boundary. */
    public static final class Capture {
        private final IdentityHashMap<Task, Reference> references = new IdentityHashMap<>();
        private final List<Task> nodes = new ArrayList<>();
        public Reference reference(Task task) {
            Objects.requireNonNull(task);
            var existing = references.get(task);
            if (existing != null) return existing;
            if (nodes.size() >= 65_536) throw new IllegalArgumentException("Too many task nodes");
            var reference = new Reference(nodes.size(), task.getId());
            references.put(task, reference);nodes.add(task);
            return reference;
        }
        /** Live nodes, including detached retry targets; inspect only inside the capture boundary. */
        public List<Task> nodes() { return List.copyOf(nodes); }
        public int size() {return nodes.size();}
        public Task node(int index) {return nodes.get(index);}
    }
    /** Bind each reconstructed graph object once, separately from TaskManager's current-child lookup. */
    public static final class Bindings {
        private final Map<Long, Task> nodes;
        public Bindings(Map<Long, Task> nodes) {
            if (nodes.size() > 65_536) throw new IllegalArgumentException("Too many task nodes");
            this.nodes = Map.copyOf(nodes);
            var identities = Collections.newSetFromMap(new IdentityHashMap<Task, Boolean>());
            for (var entry : this.nodes.entrySet()) {
                if (entry.getKey() < 0 || !identities.add(entry.getValue()))
                    throw new IllegalArgumentException("Invalid/aliased task node binding");
            }
        }
        public Task resolve(Reference reference) {
            Task task = nodes.get(reference.nodeId());
            if (task == null || task.getId() != reference.taskId())
                throw new IllegalArgumentException("Missing/mismatched task node binding");
            return task;
        }
    }
    private TaskNodeReferences() { }
}
