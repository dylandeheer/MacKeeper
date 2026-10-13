/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Execution state only. Definitions and node identities are bound to the authored tree digest. */
public final class TriggerSnapshots {
    public static final int MAX_NODES = 65536;
    private static final int MAGIC = 0x4d4b5452, VERSION = 1, MAX_BYTES = 4 * 1024 * 1024;

    /** repeat == -1 marks an action; last may reference a detached generic child. */
    public record Node(int repeat, List<Integer> children, int last) {
        public Node {
            children = List.copyOf(children);
            if (repeat < -1 || repeat > 255 || last < -1 || children.size() > MAX_NODES)
                throw new IllegalArgumentException("Invalid trigger node");
            if (repeat == -1 && (!children.isEmpty() || last != -1)) throw new IllegalArgumentException("Action has traversal state");
        }
    }
    public record State(int triggerId, String definition, boolean enabled, long initialPayday, Map<Integer,Long> paydays,
            int cursor, List<Node> nodes) {
        public State {
            Objects.requireNonNull(definition);paydays = Map.copyOf(paydays);nodes = List.copyOf(nodes);
            if (triggerId <= 0 || !definition.matches("[0-9a-f]{64}") || initialPayday < 0 || nodes.isEmpty()
                    || nodes.size() > MAX_NODES || paydays.size() > MAX_NODES || cursor < 0 || cursor >= nodes.size()
                    || nodes.get(0).repeat() < 0 || nodes.get(cursor).repeat() < 0)
                throw new IllegalArgumentException("Invalid trigger state");
            for (var entry : paydays.entrySet()) if (entry.getKey() <= 0 || entry.getValue() < initialPayday)
                throw new IllegalArgumentException("Invalid observed payday");
            int[] parents = new int[nodes.size()];int edges = 0;
            for (int i=0;i<nodes.size();i++) {
                var node = nodes.get(i);
                if (node.last() >= nodes.size() || node.last() == i || (node.last() >= 0 && nodes.get(node.last()).repeat() < 0))
                    throw new IllegalArgumentException("Invalid trigger cursor child");
                for (int child : node.children()) {
                    if (child <= 0 || child >= nodes.size() || ++parents[child] > 1 || ++edges >= nodes.size())
                        throw new IllegalArgumentException("Invalid trigger tree edge");
                }
            }
            var ready = new ArrayDeque<Integer>();for (int i=0;i<parents.length;i++) if (parents[i] == 0) ready.add(i);
            int visited = 0;
            while (!ready.isEmpty()) {
                int id = ready.removeFirst();visited++;
                for (int child : nodes.get(id).children()) if (--parents[child] == 0) ready.add(child);
            }
            if (visited != nodes.size()) throw new IllegalArgumentException("Cyclic trigger tree");
        }
    }
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(state.triggerId());TEXT.write(out,state.definition());
            BOOLEAN.write(out,state.enabled());out.writeLong(state.initialPayday());out.writeInt(state.paydays().size());
            for (var entry : new TreeMap<>(state.paydays()).entrySet()) {out.writeInt(entry.getKey());out.writeLong(entry.getValue());}
            out.writeInt(state.cursor());out.writeInt(state.nodes().size());
            for (var node : state.nodes()) {
                out.writeInt(node.repeat());out.writeInt(node.last());out.writeInt(node.children().size());
                for (int child : node.children()) out.writeInt(child);
            }
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported trigger section");
            int triggerId = in.readInt();String definition = TEXT.read(in);boolean enabled = BOOLEAN.read(in);long initial = in.readLong();
            int n = count(in.readInt(),MAX_NODES);var paydays = new TreeMap<Integer,Long>();
            for (int i=0;i<n;i++) if (paydays.put(in.readInt(),in.readLong()) != null) throw new IOException("Duplicate payday observation");
            int cursor = in.readInt();n = count(in.readInt(),MAX_NODES);int edges = 0;var nodes = new ArrayList<Node>();
            for (int i=0;i<n;i++) {
                int repeat = in.readInt(), last = in.readInt(), length = count(in.readInt(),MAX_NODES);
                edges += length;count(edges,MAX_NODES-1);var children = new ArrayList<Integer>();
                for (int c=0;c<length;c++) children.add(in.readInt());nodes.add(new Node(repeat,children,last));
            }
            var state = new State(triggerId,definition,enabled,initial,paydays,cursor,nodes);
            if (in.read() != -1) throw new IOException("Trailing trigger data");return state;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid trigger section",e);}
    }
    private TriggerSnapshots() { }
}
