/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Observed experience/training membership and per-creature accumulated working time. */
public final class ExperienceSnapshots {
    public record State(int impGain, List<EntityId> experience, List<EntityId> training, Map<EntityId,Double> workingTime) {
        public State {
            experience=ordered(experience);training=ordered(training);workingTime=Map.copyOf(workingTime);
            var members=new HashSet<>(experience);members.addAll(training);
            if(workingTime.size()>65536 || !members.containsAll(workingTime.keySet()))throw new IllegalArgumentException("Unknown working-time owner");
            for(double time:workingTime.values())if(!Double.isFinite(time) || time<0)throw new IllegalArgumentException("Invalid accumulated experience time");
        }
    }
    private static final int MAGIC=0x4d4b5850,VERSION=1,MAX_BYTES=3*1024*1024;
    private static List<EntityId> ordered(List<EntityId> input) {
        var result=List.copyOf(input);if(result.size()>65536)throw new IllegalArgumentException("Too many experience entities");
        long previous=-1;for(var id:result){if(id.getId()<=previous)throw new IllegalArgumentException("Invalid experience member order");previous=id.getId();}return result;
    }
    public static byte[] encode(State state)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(state.impGain());list(ENTITY_ID).write(out,state.experience());list(ENTITY_ID).write(out,state.training());
            out.writeInt(state.workingTime().size());for(var entry:new TreeMap<>(state.workingTime()).entrySet()){ENTITY_ID.write(out,entry.getKey());DOUBLE.write(out,entry.getValue());}
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes)throws IOException {
        count(bytes.length,MAX_BYTES);try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported experience section");
            int gain=in.readInt();var experience=list(ENTITY_ID).read(in);var training=list(ENTITY_ID).read(in);
            int n=count(in.readInt(),65536);var working=new TreeMap<EntityId,Double>();for(int i=0;i<n;i++)if(working.put(ENTITY_ID.read(in),DOUBLE.read(in))!=null)throw new IOException("Duplicate working-time owner");
            var state=new State(gain,experience,training,working);if(in.read()!=-1)throw new IOException("Trailing experience data");return state;
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid experience section",e);}
    }
    private ExperienceSnapshots() { }
}
