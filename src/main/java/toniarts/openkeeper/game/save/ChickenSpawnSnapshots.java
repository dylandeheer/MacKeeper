/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.RoomState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Last-observed coop/food ownership and ordered generator choices. */
public final class ChickenSpawnSnapshots {
    public record Settings(int cooldown, int maximumFreeRange) { }
    public record State(double elapsed, Settings settings, List<Short> players, List<EntityId> entrances,
            Map<EntityId,EntityId> coops, Map<EntityId,List<EntityId>> roomCoops,
            Map<EntityId,Short> owners, Map<Short,List<EntityId>> populations, List<RoomState> removedRooms) {
        public State {
            Objects.requireNonNull(settings);players=List.copyOf(players);entrances=List.copyOf(entrances);removedRooms=List.copyOf(removedRooms);
            coops=Collections.unmodifiableMap(new HashMap<>(coops));owners=Map.copyOf(owners);
            var copiedRooms=new HashMap<EntityId,List<EntityId>>();roomCoops.forEach((id,values)->copiedRooms.put(id,List.copyOf(values)));roomCoops=Map.copyOf(copiedRooms);
            var copiedPlayers=new HashMap<Short,List<EntityId>>();populations.forEach((id,values)->copiedPlayers.put(id,List.copyOf(values)));populations=Map.copyOf(copiedPlayers);
            if(!Double.isFinite(elapsed) || elapsed<0 || settings.maximumFreeRange()<0 || players.size()>256 || populations.size()>256
                    || entrances.size()>65536 || coops.size()>65536 || roomCoops.size()>65536 || owners.size()>65536 || removedRooms.size()>65536
                    || new HashSet<>(players).size()!=players.size())throw new IllegalArgumentException("Invalid chicken spawning state");
            players.forEach(ChickenSpawnSnapshots::player);populations.keySet().forEach(ChickenSpawnSnapshots::player);owners.values().forEach(ChickenSpawnSnapshots::player);
            entrances.forEach(ChickenSpawnSnapshots::entity);coops.keySet().forEach(ChickenSpawnSnapshots::entity);roomCoops.keySet().forEach(ChickenSpawnSnapshots::entity);
            owners.keySet().forEach(ChickenSpawnSnapshots::entity);
            if(!populations.keySet().containsAll(players))throw new IllegalArgumentException("Missing player population");
            var groupedCoops=new HashSet<EntityId>();
            for(var group:roomCoops.entrySet())for(var id:group.getValue()) {
                if(!groupedCoops.add(id) || !group.getKey().equals(coops.get(id)))throw new IllegalArgumentException("Inconsistent coop room index");
            }
            for(var entry:coops.entrySet())if(entry.getValue()!=null && !groupedCoops.contains(entry.getKey()))throw new IllegalArgumentException("Unindexed room coop");
            var groupedFood=new HashSet<EntityId>();
            for(var group:populations.entrySet()) {
                long previous=-1;
                for(var id:group.getValue()) {
                    if(id.getId()<=previous || !groupedFood.add(id) || !group.getKey().equals(owners.get(id)))throw new IllegalArgumentException("Inconsistent chicken population");
                    previous=id.getId();
                }
            }
            if(!groupedFood.equals(owners.keySet()))throw new IllegalArgumentException("Unindexed chicken owner");
            var references=new HashSet<>(entrances);references.addAll(roomCoops.keySet());var removed=new HashSet<EntityId>();
            for(var room:removedRooms)if(!removed.add(room.entityId()) || !references.contains(room.entityId()) || !room.destroyed())throw new IllegalArgumentException("Invalid removed hatchery");
        }
    }
    private static final int MAGIC=0x4d4b4847,VERSION=1,MAX_BYTES=72*1024*1024;
    private static void entity(EntityId id){if(id==null || id.getId()<0)throw new IllegalArgumentException("Invalid spawning entity");}
    private static void player(short id){if(id<0 || id>255)throw new IllegalArgumentException("Invalid chicken owner");}
    public static byte[] encode(State state)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);DOUBLE.write(out,state.elapsed());out.writeInt(state.settings().cooldown());out.writeInt(state.settings().maximumFreeRange());
            list(SHORT).write(out,state.players());list(ENTITY_ID).write(out,state.entrances());
            out.writeInt(state.coops().size());for(var entry:new TreeMap<>(state.coops()).entrySet()){ENTITY_ID.write(out,entry.getKey());nullable(ENTITY_ID).write(out,entry.getValue());}
            out.writeInt(state.roomCoops().size());for(var entry:new TreeMap<>(state.roomCoops()).entrySet()){ENTITY_ID.write(out,entry.getKey());list(ENTITY_ID).write(out,entry.getValue());}
            out.writeInt(state.owners().size());for(var entry:new TreeMap<>(state.owners()).entrySet()){ENTITY_ID.write(out,entry.getKey());out.writeShort(entry.getValue());}
            out.writeInt(state.populations().size());for(var entry:new TreeMap<>(state.populations()).entrySet()){out.writeShort(entry.getKey());list(ENTITY_ID).write(out,entry.getValue());}
            var rooms=RoomSnapshots.encode(state.removedRooms());out.writeInt(rooms.length);out.write(rooms);
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes)throws IOException {
        count(bytes.length,MAX_BYTES);try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported chicken spawn section");
            double elapsed=DOUBLE.read(in);var settings=new Settings(in.readInt(),in.readInt());
            int n=count(in.readInt(),256);var players=new ArrayList<Short>();for(int i=0;i<n;i++)players.add(in.readShort());var entrances=list(ENTITY_ID).read(in);
            n=count(in.readInt(),65536);var coops=new HashMap<EntityId,EntityId>();
            for(int i=0;i<n;i++){var id=ENTITY_ID.read(in);if(coops.containsKey(id))throw new IOException("Duplicate coop");coops.put(id,nullable(ENTITY_ID).read(in));}
            n=count(in.readInt(),65536);var roomCoops=new HashMap<EntityId,List<EntityId>>();int total=0;
            for(int i=0;i<n;i++){var id=ENTITY_ID.read(in);var values=list(ENTITY_ID).read(in);total+=values.size();count(total,65536);if(roomCoops.put(id,values)!=null)throw new IOException("Duplicate coop room");}
            n=count(in.readInt(),65536);var owners=new HashMap<EntityId,Short>();for(int i=0;i<n;i++)if(owners.put(ENTITY_ID.read(in),in.readShort())!=null)throw new IOException("Duplicate chicken");
            n=count(in.readInt(),256);var populations=new HashMap<Short,List<EntityId>>();total=0;
            for(int i=0;i<n;i++){short id=in.readShort();var values=list(ENTITY_ID).read(in);total+=values.size();count(total,65536);if(populations.put(id,values)!=null)throw new IOException("Duplicate population");}
            int size=count(in.readInt(),64*1024*1024);if(size>in.available())throw new EOFException("Truncated hatchery rooms");
            var state=new State(elapsed,settings,players,entrances,coops,roomCoops,owners,populations,RoomSnapshots.decode(in.readNBytes(size)));
            if(in.read()!=-1)throw new IOException("Trailing chicken spawn data");return state;
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid chicken spawn section",e);}
    }
    private ChickenSpawnSnapshots() { }
}
