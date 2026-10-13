/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.RoomState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Recruitment clock and ordered entrance references, including removed room dependencies. */
public final class CreatureSpawnSnapshots {
    public record Settings(int minimumImps, int entranceCooldown, int initialCapacity, int additionalCapacity, int freeImpCooldown) { }
    public record State(double elapsed, Settings settings, List<Short> players, List<EntityId> entrances, List<RoomState> removedRooms) {
        public State {
            Objects.requireNonNull(settings);players=List.copyOf(players);entrances=List.copyOf(entrances);removedRooms=List.copyOf(removedRooms);
            if (!Double.isFinite(elapsed) || elapsed<0 || players.size()>256 || entrances.size()>65536 || removedRooms.size()>65536
                    || new HashSet<>(players).size()!=players.size()) throw new IllegalArgumentException("Invalid creature spawn state");
            for(short owner:players) if(owner<0 || owner>255)throw new IllegalArgumentException("Invalid spawn owner");
            for(var id:entrances)if(id.getId()<0)throw new IllegalArgumentException("Invalid entrance ID");
            var references=new HashSet<>(entrances);var removed=new HashSet<EntityId>();
            for(var room:removedRooms)if(!removed.add(room.entityId()) || !references.contains(room.entityId()) || !room.destroyed())
                throw new IllegalArgumentException("Invalid removed entrance");
        }
    }
    private static final int MAGIC=0x4d4b4347,VERSION=1,MAX_BYTES=65*1024*1024;
    public static byte[] encode(State state)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);DOUBLE.write(out,state.elapsed());var settings=state.settings();
            out.writeInt(settings.minimumImps());out.writeInt(settings.entranceCooldown());out.writeInt(settings.initialCapacity());
            out.writeInt(settings.additionalCapacity());out.writeInt(settings.freeImpCooldown());
            list(SHORT).write(out,state.players());list(ENTITY_ID).write(out,state.entrances());
            var rooms=RoomSnapshots.encode(state.removedRooms());out.writeInt(rooms.length);out.write(rooms);
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes)throws IOException {
        count(bytes.length,MAX_BYTES);try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported creature spawn section");
            double elapsed=DOUBLE.read(in);var settings=new Settings(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
            int n=count(in.readInt(),256);var players=new ArrayList<Short>();for(int i=0;i<n;i++)players.add(in.readShort());
            var entrances=list(ENTITY_ID).read(in);int size=count(in.readInt(),64*1024*1024);
            if(size>in.available())throw new EOFException("Truncated entrance rooms");var rooms=RoomSnapshots.decode(in.readNBytes(size));
            var state=new State(elapsed,settings,players,entrances,rooms);if(in.read()!=-1)throw new IOException("Trailing creature spawn data");return state;
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid creature spawn section",e);}
    }
    private CreatureSpawnSnapshots() { }
}
