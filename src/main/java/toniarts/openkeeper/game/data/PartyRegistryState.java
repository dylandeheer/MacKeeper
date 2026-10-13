/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import java.util.*;

/** Two indexes can share party objects; list positions are snapshot-local node identities. */
public record PartyRegistryState(long nextId, List<PartyState> nodes,
        Map<Short, Integer> current, Map<Long, Integer> spawned) {
    public PartyRegistryState {
        nodes = List.copyOf(nodes);current = Map.copyOf(current);spawned = Map.copyOf(spawned);
        if (nextId < 1 || nodes.size() > 65_536 || current.size() > 256 || spawned.size() > 65_536)
            throw new IllegalArgumentException("Invalid party registry bounds");
        var runtimeIds = new HashSet<Long>();
        for (var node : nodes) if (node.created() && (node.id() >= nextId || !runtimeIds.add(node.id())))
            throw new IllegalArgumentException("Invalid allocated party identity");
        var referenced = new HashSet<Integer>();
        for (var entry : current.entrySet()) {
            int index = entry.getValue();
            if (index < 0 || index >= nodes.size() || nodes.get(index).definitionId() != entry.getKey())
                throw new IllegalArgumentException("Invalid current party index");
            referenced.add(index);
        }
        for (var entry : spawned.entrySet()) {
            int index = entry.getValue();
            if (index < 0 || index >= nodes.size() || !nodes.get(index).created() || nodes.get(index).id() != entry.getKey())
                throw new IllegalArgumentException("Invalid spawned party index");
            referenced.add(index);
        }
        if (referenced.size() != nodes.size()) throw new IllegalArgumentException("Unreachable party node");
    }
}
