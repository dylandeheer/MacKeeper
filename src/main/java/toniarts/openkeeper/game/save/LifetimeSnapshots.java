/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Local system clocks and death membership whose addition performs possession cleanup. */
public final class LifetimeSnapshots {
    public record Clock(double elapsed) {
        public Clock {if(!Double.isFinite(elapsed) || elapsed<0)throw new IllegalArgumentException("Invalid system time");}
    }
    public record DeathState(Clock clock,int decayDelay,List<EntityId> observed) {
        public DeathState {
            Objects.requireNonNull(clock);observed=List.copyOf(observed);
            if(observed.size()>65536)throw new IllegalArgumentException("Too many observed deaths");
            long previous=-1;
            for(var id:observed){if(id.getId()<=previous)throw new IllegalArgumentException("Invalid death membership order");previous=id.getId();}
        }
    }
    public record State(Clock decay,Clock torture,DeathState death) {
        public State {Objects.requireNonNull(decay);Objects.requireNonNull(torture);Objects.requireNonNull(death);}
    }
    private static final int MAGIC=0x4d4b4c46,VERSION=1,MAX_BYTES=1024*1024;
    public static byte[] encode(State state)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);DOUBLE.write(out,state.decay().elapsed());DOUBLE.write(out,state.torture().elapsed());
            DOUBLE.write(out,state.death().clock().elapsed());out.writeInt(state.death().decayDelay());list(ENTITY_ID).write(out,state.death().observed());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes)throws IOException {
        count(bytes.length,MAX_BYTES);try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported lifetime section");
            var decay=new Clock(DOUBLE.read(in));var torture=new Clock(DOUBLE.read(in));var death=new Clock(DOUBLE.read(in));
            var state=new State(decay,torture,new DeathState(death,in.readInt(),list(ENTITY_ID).read(in)));
            if(in.read()!=-1)throw new IOException("Trailing lifetime data");return state;
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid lifetime section",e);}
    }
    private LifetimeSnapshots() { }
}
