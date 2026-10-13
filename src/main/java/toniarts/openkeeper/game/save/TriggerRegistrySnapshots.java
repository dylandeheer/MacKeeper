/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Ordered trigger owners and last-observed thing-query membership. */
public final class TriggerRegistrySnapshots {
    public enum Kind { LEVEL, PLAYER, PARTY, ACTION_POINT, CREATURE, OBJECT, DOOR;
        public boolean thing() {return this == CREATURE || this == OBJECT || this == DOOR;}
    }
    /** Unused owner fields have canonical sentinel values; party instance 0 means not yet spawned. */
    public record Entry(TriggerSnapshots.State tree, int ownerId, long partyInstance, short playerId,
            EntityId entity, short definition) {
        public Entry {Objects.requireNonNull(tree);}
        public static Entry level(TriggerSnapshots.State tree) {return new Entry(tree,0,0,(short)-1,null,(short)-1);}
        public static Entry player(TriggerSnapshots.State tree, short player) {return new Entry(tree,0,0,player,null,(short)-1);}
        public static Entry actionPoint(TriggerSnapshots.State tree, int id) {return new Entry(tree,id,0,(short)-1,null,(short)-1);}
        public static Entry party(TriggerSnapshots.State tree, short definition, long instance) {return new Entry(tree,definition,instance,(short)-1,null,(short)-1);}
        public static Entry thing(TriggerSnapshots.State tree, short player, EntityId entity, short definition) {return new Entry(tree,0,0,player,entity,definition);}
    }
    public record State(Kind kind, List<Entry> entries, List<EntityId> observed) {
        public State {
            Objects.requireNonNull(kind);entries = List.copyOf(entries);observed = List.copyOf(observed);
            if (entries.size() > 4096 || observed.size() > 65536 || !kind.thing() && !observed.isEmpty()
                    || kind == Kind.LEVEL && entries.size() > 1) throw new IllegalArgumentException("Invalid trigger registry size");
            long previous = -1;
            for (var id : observed) {if (id.getId() <= previous) throw new IllegalArgumentException("Invalid trigger membership order");previous=id.getId();}
            var keys = new HashSet<Integer>();long nodes = 0, paydays = 0;
            for (var entry : entries) {
                boolean valid = switch (kind) {
                    case LEVEL -> entry.ownerId()==0 && entry.partyInstance()==0 && entry.playerId()==-1 && entry.entity()==null && entry.definition()==-1;
                    case PLAYER -> entry.ownerId()==0 && entry.partyInstance()==0 && player(entry.playerId()) && entry.entity()==null && entry.definition()==-1;
                    case ACTION_POINT -> entry.ownerId()>0 && entry.partyInstance()==0 && entry.playerId()==-1 && entry.entity()==null && entry.definition()==-1;
                    case PARTY -> entry.ownerId()>=0 && entry.ownerId()<=255 && entry.partyInstance()>=0 && entry.playerId()==-1 && entry.entity()==null && entry.definition()==-1;
                    default -> entry.ownerId()==0 && entry.partyInstance()==0 && player(entry.playerId()) && (entry.entity()==null ? entry.definition()==-1 : entry.entity().getId()>=0 && entry.definition()>=0 && entry.definition()<=255);
                };
                int key = kind.thing() ? entry.tree().triggerId() : kind == Kind.PLAYER ? entry.playerId() : entry.ownerId();
                if (!valid || !keys.add(key)) throw new IllegalArgumentException("Invalid/duplicate trigger owner");
                nodes += entry.tree().nodes().size();paydays += entry.tree().paydays().size();
            }
            if (nodes > 262144 || paydays > 262144) throw new IllegalArgumentException("Trigger registry too large");
        }
    }
    private static boolean player(short id) {return id>=0 && id<=255;}
    private static final int MAGIC=0x4d4b5447, VERSION=1, MAX_BYTES=32*1024*1024;
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try(var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);enumeration(Kind.class).write(out,state.kind());out.writeInt(state.entries().size());
            for(var entry:state.entries()) {
                var tree = TriggerSnapshots.encode(entry.tree());out.writeInt(tree.length);out.write(tree);
                out.writeInt(entry.ownerId());out.writeLong(entry.partyInstance());out.writeShort(entry.playerId());
                nullable(ENTITY_ID).write(out,entry.entity());out.writeShort(entry.definition());
            }
            list(ENTITY_ID).write(out,state.observed());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported trigger registry");
            var kind=enumeration(Kind.class).read(in);int size=count(in.readInt(),4096);var entries=new ArrayList<Entry>();
            long nodes=0, paydays=0;
            for(int i=0;i<size;i++) {
                int length=count(in.readInt(),4*1024*1024);if(length>in.available())throw new EOFException("Truncated trigger tree");
                var tree=TriggerSnapshots.decode(in.readNBytes(length));
                nodes += tree.nodes().size();paydays += tree.paydays().size();
                if(nodes>262144 || paydays>262144)throw new IOException("Trigger registry too large");
                entries.add(new Entry(tree,in.readInt(),in.readLong(),in.readShort(),nullable(ENTITY_ID).read(in),in.readShort()));
            }
            int count=count(in.readInt(),65536);var observed=new ArrayList<EntityId>();
            for(int i=0;i<count;i++)observed.add(ENTITY_ID.read(in));
            var state=new State(kind,entries,observed);if(in.read()!=-1)throw new IOException("Trailing trigger registry data");return state;
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid trigger registry",e);}
    }
    private TriggerRegistrySnapshots() { }
}
