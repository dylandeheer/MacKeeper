/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.trigger;

import java.io.*;
import java.security.*;
import java.util.*;
import toniarts.openkeeper.game.save.TriggerSnapshots;
import toniarts.openkeeper.tools.convert.map.TriggerGeneric;

/** Stable occurrence identities include consumed nodes and detached execution cursors. */
final class TriggerTree {
    private final int triggerId;
    private final List<TriggerData> nodes;
    private final IdentityHashMap<TriggerData,Integer> identities = new IdentityHashMap<>();
    private final List<TriggerSnapshots.Node> original;
    private final String definition;
    private final Set<Integer> paydayIds = new HashSet<>();

    TriggerTree(int triggerId, TriggerGenericData root) {
        this.triggerId = triggerId;
        var ordered = new ArrayList<TriggerData>();var pending = new ArrayDeque<TriggerData>();pending.push(root);
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (identities.put(node,ordered.size()) != null || ordered.size() >= TriggerSnapshots.MAX_NODES)
                throw new IllegalArgumentException("Invalid authored trigger tree");
            ordered.add(node);
            if (node instanceof TriggerGenericData generic) {
                for (var child : generic.getChildren().reversed()) pending.push(child);
                if (generic.getType() == TriggerGeneric.TargetType.LEVEL_PAY_DAY) paydayIds.add(generic.getId());
            }
        }
        nodes = List.copyOf(ordered);original = captureNodes();definition = digest();
    }

    private int identity(TriggerData node) {
        var id = identities.get(node);
        if (id == null) throw new IllegalStateException("Trigger node was not authored in this tree");return id;
    }
    private List<TriggerSnapshots.Node> captureNodes() {
        return nodes.stream().map(node -> node instanceof TriggerGenericData generic
                ? new TriggerSnapshots.Node(generic.getRepeatTimes(),generic.getChildren().stream().map(this::identity).toList(),
                        generic.getLastTrigger() == null ? -1 : identity(generic.getLastTrigger()))
                : new TriggerSnapshots.Node(-1,List.of(),-1)).toList();
    }
    TriggerSnapshots.State capture(boolean enabled, long initial, Map<Integer,Long> paydays, TriggerGenericData cursor) {
        var state = new TriggerSnapshots.State(triggerId,definition,enabled,initial,paydays,identity(cursor),captureNodes());
        validate(state);return state;
    }
    void validate(TriggerSnapshots.State saved) {
        if (saved.triggerId() != triggerId || !definition.equals(digest()) || !definition.equals(saved.definition()) || saved.nodes().size() != nodes.size()
                || !paydayIds.containsAll(saved.paydays().keySet())) throw new IllegalArgumentException("Trigger definition differs");
        for (int i=0;i<nodes.size();i++) {
            var before = original.get(i);var after = saved.nodes().get(i);
            if ((before.repeat() == -1) != (after.repeat() == -1) || after.repeat() > before.repeat()
                    || (before.repeat() == 255 && after.repeat() != 255)) throw new IllegalArgumentException("Invalid trigger repeat progress");
            // Runtime only removes children; it cannot reorder or move them between parents.
            int offset = 0;
            for (int child : after.children()) {
                while (offset < before.children().size() && before.children().get(offset) != child) offset++;
                if (offset == before.children().size()) throw new IllegalArgumentException("Trigger child order differs");offset++;
            }
            if (after.last() >= 0 && !before.children().contains(after.last())) throw new IllegalArgumentException("Unknown last trigger child");
        }
    }
    TriggerGenericData restore(TriggerSnapshots.State saved) {
        validate(saved);
        for (var node : nodes) if (node instanceof TriggerGenericData generic) generic.detachAllChildren();
        for (int i=0;i<nodes.size();i++) if (nodes.get(i) instanceof TriggerGenericData generic) {
            var state = saved.nodes().get(i);generic.setRepeatTimes((short)state.repeat());
            for (int child : state.children()) generic.attachChild(nodes.get(child));
            generic.setLastTrigger(state.last() < 0 ? null : (TriggerGenericData)nodes.get(state.last()));
        }
        return (TriggerGenericData)nodes.get(saved.cursor());
    }

    private String digest() {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash))) {
                out.writeInt(triggerId);out.writeInt(nodes.size());
                for (int i=0;i<nodes.size();i++) {
                    var node = nodes.get(i);out.writeInt(node.getId());
                    if (node instanceof TriggerGenericData generic) {
                        out.writeUTF("generic");out.writeUTF(generic.getType() == null ? "" : generic.getType().name());
                        out.writeUTF(generic.getComparison() == null ? "" : generic.getComparison().name());
                    } else {out.writeUTF("action");out.writeUTF(((TriggerActionData)node).getType().name());}
                    var keys = new TreeSet<>(node.getUserDataKeys());out.writeInt(keys.size());
                    for (var key : keys) {
                        Number value = node.getUserData(key);
                        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                                || value instanceof Float || value instanceof Double)) throw new IllegalArgumentException("Unsupported trigger value");
                        out.writeUTF(key);out.writeUTF(value.getClass().getSimpleName());out.writeUTF(value.toString());
                    }
                    var initial = original.get(i);out.writeInt(initial.repeat());out.writeInt(initial.children().size());
                    for (int child : initial.children()) out.writeInt(child);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (IOException | NoSuchAlgorithmException e) {throw new IllegalStateException("Cannot identify trigger definition",e);}
    }
}
