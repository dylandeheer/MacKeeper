/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.state.session.PresentationEvent.Vec3;
import toniarts.openkeeper.gui.nifty.AbstractCreatureCardControl.CreatureUIState;
import toniarts.openkeeper.gui.nifty.WorkerAmountControl;
import toniarts.openkeeper.view.creature.CreatureCardsState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKCC v1: explicit observed card values, ordered membership and click/scroll positions. */
public final class CreatureCardSnapshots {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static byte[] encode(CreatureCardsState state) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b4343);out.writeInt(1);out.writeShort(state.playerId());out.writeShort(state.workerId());
            out.writeInt(state.creatures().size());
            for(var value:state.creatures()) {
                ENTITY_ID.write(out,value.id());BOOLEAN.write(out,value.type()!=null);
                if(value.type()!=null) {
                    out.writeShort(value.type());BOOLEAN.write(out,value.position()!=null);
                    if(value.position()!=null) {FLOAT.write(out,value.position().x());FLOAT.write(out,value.position().y());FLOAT.write(out,value.position().z());}
                    enumeration(CreatureUIState.class).write(out,value.activity());BOOLEAN.write(out,value.dead());
                }
            }
            out.writeInt(state.groups().size());
            for(var group:state.groups()) {
                out.writeShort(group.type());out.writeInt(group.ids().size());for(var id:group.ids()) ENTITY_ID.write(out,id);
            }
            out.writeInt(state.cards().size());for(short type:state.cards()) out.writeShort(type);
            out.writeInt(state.nextIndices().size());
            for(var entry:new TreeMap<>(state.nextIndices()).entrySet()) {out.writeShort(entry.getKey());out.writeInt(entry.getValue());}
            enumeration(WorkerAmountControl.State.class).write(out,state.mode());out.writeInt(state.firstVisible());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static CreatureCardsState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0x4d4b4343 || in.readInt()!=1) throw new IOException("Unsupported creature card section");
            short player=in.readShort(),worker=in.readShort();int n=count(in.readInt(),CreatureCardsState.MAX_CREATURES);
            var observations=new ArrayList<CreatureCardsState.Observation>();
            for(int i=0;i<n;i++) {
                var id=ENTITY_ID.read(in);Short type=null;Vec3 position=null;CreatureUIState activity=null;boolean dead=false;
                if(BOOLEAN.read(in)) {
                    type=in.readShort();if(BOOLEAN.read(in)) position=new Vec3(FLOAT.read(in),FLOAT.read(in),FLOAT.read(in));
                    activity=enumeration(CreatureUIState.class).read(in);dead=BOOLEAN.read(in);
                }
                observations.add(new CreatureCardsState.Observation(id,type,position,activity,dead));
            }
            int groupsCount=count(in.readInt(),256),remaining=CreatureCardsState.MAX_CREATURES;
            var groups=new ArrayList<CreatureCardsState.Group>();
            for(int i=0;i<groupsCount;i++) {
                short type=in.readShort();n=count(in.readInt(),remaining);remaining-=n;var ids=new ArrayList<EntityId>();
                for(int j=0;j<n;j++) ids.add(ENTITY_ID.read(in));groups.add(new CreatureCardsState.Group(type,ids));
            }
            n=count(in.readInt(),255);var cards=new ArrayList<Short>();for(int i=0;i<n;i++) cards.add(in.readShort());
            n=count(in.readInt(),256);var indices=new HashMap<Short,Integer>();
            for(int i=0;i<n;i++) if(indices.put(in.readShort(),in.readInt())!=null) throw new IOException("Duplicate creature cycle index");
            var state=new CreatureCardsState(player,worker,observations,groups,cards,indices,
                    enumeration(WorkerAmountControl.State.class).read(in),in.readInt());
            if(in.read()!=-1) throw new IOException("Trailing creature card data");return state;
        } catch(IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid creature card section",failure);}
    }
    private CreatureCardSnapshots() {}
}
