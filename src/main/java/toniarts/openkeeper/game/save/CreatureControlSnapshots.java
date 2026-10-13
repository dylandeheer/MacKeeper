/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.CreatureControlState;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** One creature controller; task object contents belong to the separate task graph. */
public final class CreatureControlSnapshots {
    static final int MAGIC = 0x4d4b4352; // MKCR
    static final int VERSION = 1;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Value<CreatureState> STATE = nullable(enumeration(CreatureState.class));
    public static byte[] encode(CreatureControlState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);ENTITY_ID.write(out,state.entityId());out.writeShort(state.creatureType());
            STATE.write(out,state.current());STATE.write(out,state.previous());STATE.write(out,state.global());
            FLOAT.write(out,state.motionless());DOUBLE.write(out,state.nextPayCheck());BOOLEAN.write(out,state.failedTask() != null);
            if (state.failedTask() != null) {LONG.write(out,state.failedTask().nodeId());LONG.write(out,state.failedTask().taskId());}
            nullable(POINT).write(out,state.failedStart());nullable(POINT).write(out,state.failedTarget());DOUBLE.write(out,state.failedTime());
            out.writeInt(state.heartPaths().size());
            for (var path:state.heartPaths()) {ENTITY_ID.write(out,path.roomId());POINT.write(out,path.start());DOUBLE.write(out,path.time());}
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static CreatureControlState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("Unsupported creature controller section");
            var id=ENTITY_ID.read(in);short type=in.readShort();var current=STATE.read(in);var previous=STATE.read(in);var global=STATE.read(in);
            float motionless=FLOAT.read(in);double pay=DOUBLE.read(in);TaskNodeReferences.Reference task = BOOLEAN.read(in) ? new TaskNodeReferences.Reference(LONG.read(in), LONG.read(in)) : null;
            var start=nullable(POINT).read(in);var target=nullable(POINT).read(in);double time=DOUBLE.read(in);
            int n=count(in.readInt(),65_536);var paths=new ArrayList<CreatureControlState.HeartPath>();
            for(int i=0;i<n;i++) paths.add(new CreatureControlState.HeartPath(ENTITY_ID.read(in),POINT.read(in),DOUBLE.read(in)));
            if(in.read()!=-1) throw new IOException("Trailing creature controller data");
            return new CreatureControlState(id,type,current,previous,global,motionless,pay,task,start,target,time,paths);
        } catch(IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid creature controller section",e);}
    }
    private CreatureControlSnapshots() { }
}
