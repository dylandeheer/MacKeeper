/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.PartyState;
import toniarts.openkeeper.game.data.PartyRegistryState;
import toniarts.openkeeper.game.controller.creature.PartyType;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Explicit party nodes, shared indexes and the per-world allocation cursor. */
public final class PartySnapshots {
    private static final int MAGIC = 0x4d4b5059; // MKPY
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    public static byte[] encode(PartyRegistryState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);LONG.write(out,state.nextId());out.writeInt(state.nodes().size());
            for (var node : state.nodes()) {
                LONG.write(out,node.id());SHORT.write(out,node.definitionId());BOOLEAN.write(out,node.created());
                nullable(enumeration(PartyType.class)).write(out,node.type());nullable(ENTITY_ID).write(out,node.leader());
                out.writeInt(node.members().size());
                for (var member : node.members()) {INT.write(out,member.definitionIndex());nullable(ENTITY_ID).write(out,member.creature());}
                count(bytes.size(),MAX_BYTES);
            }
            out.writeInt(state.current().size());
            for (var entry : new TreeMap<>(state.current()).entrySet()) {SHORT.write(out,entry.getKey());INT.write(out,entry.getValue());}
            out.writeInt(state.spawned().size());
            for (var entry : new TreeMap<>(state.spawned()).entrySet()) {LONG.write(out,entry.getKey());INT.write(out,entry.getValue());}
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static PartyRegistryState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("Unsupported party section");
            long next = LONG.read(in);int n = count(in.readInt(),65_536);var nodes = new ArrayList<PartyState>();
            for (int i=0;i<n;i++) {
                long id=LONG.read(in);short definition=SHORT.read(in);boolean created=BOOLEAN.read(in);
                var type=nullable(enumeration(PartyType.class)).read(in);var leader=nullable(ENTITY_ID).read(in);
                int length=count(in.readInt(),65_536);var members=new ArrayList<PartyState.Member>();
                for(int j=0;j<length;j++) members.add(new PartyState.Member(INT.read(in),nullable(ENTITY_ID).read(in)));
                nodes.add(new PartyState(id,definition,created,type,leader,members));
            }
            int currentCount=count(in.readInt(),256);var current=new TreeMap<Short,Integer>();
            for(int i=0;i<currentCount;i++) if(current.put(SHORT.read(in),INT.read(in))!=null) throw new IOException("Duplicate current party");
            int spawnedCount=count(in.readInt(),65_536);var spawned=new TreeMap<Long,Integer>();
            for(int i=0;i<spawnedCount;i++) if(spawned.put(LONG.read(in),INT.read(in))!=null) throw new IOException("Duplicate spawned party");
            if(in.read()!=-1) throw new IOException("Trailing party data");return new PartyRegistryState(next,nodes,current,spawned);
        } catch(IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid party section",e);}
    }
    private PartySnapshots() { }
}
