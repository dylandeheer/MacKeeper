/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Last-observed mana sources and the fractional update clock, without applying pending changes. */
public final class ManaSnapshots {
    public record Source(short player, int generation) {
        public Source {ManaSnapshots.player(player);}
    }
    public record State(float tick, List<Short> players, Map<EntityId,Source> sources,
            Map<Short,Integer> gains, Map<Short,Integer> losses, List<EntityId> dirty) {
        public State {
            if(!Float.isFinite(tick) || tick<0)throw new IllegalArgumentException("Invalid mana clock");
            players=List.copyOf(players);sources=Map.copyOf(sources);gains=Map.copyOf(gains);losses=Map.copyOf(losses);dirty=List.copyOf(dirty);
            if(players.size()>256 || sources.size()>65536 || dirty.size()>65536 || new HashSet<>(players).size()!=players.size()
                    || new HashSet<>(dirty).size()!=dirty.size())throw new IllegalArgumentException("Invalid mana section size/duplicates");
            players.forEach(ManaSnapshots::player);sources.keySet().forEach(ManaSnapshots::entity);
            if(!sources.keySet().containsAll(dirty) || !players.containsAll(gains.keySet()) || !players.containsAll(losses.keySet()))
                throw new IllegalArgumentException("Unknown mana source/player");
            var expectedGains=new HashMap<Short,Integer>();var expectedLosses=new HashMap<Short,Integer>();
            for(var source:sources.values())if(players.contains(source.player())) {
                if(source.generation()>0)expectedGains.merge(source.player(),source.generation(),Integer::sum);
                if(source.generation()<0)expectedLosses.merge(source.player(),-source.generation(),Integer::sum);
            }
            for(short id:players)if(!gains.getOrDefault(id,0).equals(expectedGains.getOrDefault(id,0))
                    || !losses.getOrDefault(id,0).equals(expectedLosses.getOrDefault(id,0)))throw new IllegalArgumentException("Mana totals differ from observed sources");
        }
    }
    private static final int MAGIC=0x4d4b4d41,VERSION=1,MAX_BYTES=2*1024*1024;
    private static void player(short id){if(id<0 || id>255)throw new IllegalArgumentException("Invalid mana player");}
    private static void entity(EntityId id){if(id.getId()<0)throw new IllegalArgumentException("Invalid mana entity");}
    public static byte[] encode(State state)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);FLOAT.write(out,state.tick());list(SHORT).write(out,state.players());
            out.writeInt(state.sources().size());for(var entry:new TreeMap<>(state.sources()).entrySet()){ENTITY_ID.write(out,entry.getKey());out.writeShort(entry.getValue().player());out.writeInt(entry.getValue().generation());}
            totals(out,state.gains());totals(out,state.losses());list(ENTITY_ID).write(out,state.dirty());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes)throws IOException {
        count(bytes.length,MAX_BYTES);try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported mana section");
            float tick=FLOAT.read(in);int p=count(in.readInt(),256);var players=new ArrayList<Short>();for(int i=0;i<p;i++)players.add(in.readShort());
            int n=count(in.readInt(),65536);var sources=new TreeMap<EntityId,Source>();
            for(int i=0;i<n;i++)if(sources.put(ENTITY_ID.read(in),new Source(in.readShort(),in.readInt()))!=null)throw new IOException("Duplicate mana source");
            var state=new State(tick,players,sources,totals(in),totals(in),list(ENTITY_ID).read(in));
            if(in.read()!=-1)throw new IOException("Trailing mana data");return state;
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid mana section",e);}
    }
    private static void totals(DataOutput out,Map<Short,Integer> totals)throws IOException{out.writeInt(totals.size());for(var e:new TreeMap<>(totals).entrySet()){out.writeShort(e.getKey());out.writeInt(e.getValue());}}
    private static Map<Short,Integer> totals(DataInput in)throws IOException{int n=count(in.readInt(),256);var totals=new TreeMap<Short,Integer>();for(int i=0;i<n;i++)if(totals.put(in.readShort(),in.readInt())!=null)throw new IOException("Duplicate mana total");return totals;}
    private ManaSnapshots() { }
}
