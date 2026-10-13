/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.time.Instant;
import java.util.*;
import toniarts.openkeeper.game.data.TaskBaseState;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.worker.*;
import toniarts.openkeeper.game.task.objective.ObjectiveDigTileTask;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Executable tile-work nodes; task graph edges and other task kinds have separate owners. */
public final class TileTaskSnapshots {
    public enum Kind { DIG, CLAIM_TILE, CLAIM_ROOM, CLAIM_WALL, REPAIR_WALL, OBJECTIVE_DIG }
    public record State(Kind kind, TaskBaseState base, Point location, short player) {
        public State {
            Objects.requireNonNull(kind);Objects.requireNonNull(base);Objects.requireNonNull(location);
            if (player < 0 || player > 255 || location.x < 0 || location.y < 0) throw new IllegalArgumentException("Invalid tile task target");
        }
    }
    private static final int MAGIC = 0x4d4b5454; // MKTT
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<Class<?>> SUPPORTED = Set.of(DigTileTask.class, ClaimTileTask.class, ClaimRoomTask.class,
            ClaimWallTileTask.class, RepairWallTileTask.class, ObjectiveDigTileTask.class);
    public static boolean supports(Task task) {return SUPPORTED.contains(task.getClass());}
    public static State capture(AbstractTileTask task) {
        Kind kind;
        if(task.getClass()==DigTileTask.class) kind=Kind.DIG;
        else if(task.getClass()==ClaimTileTask.class) kind=Kind.CLAIM_TILE;
        else if(task.getClass()==ClaimRoomTask.class) kind=Kind.CLAIM_ROOM;
        else if(task.getClass()==ClaimWallTileTask.class) kind=Kind.CLAIM_WALL;
        else if(task.getClass()==RepairWallTileTask.class) kind=Kind.REPAIR_WALL;
        else if(task.getClass()==ObjectiveDigTileTask.class) kind=Kind.OBJECTIVE_DIG;
        else throw new IllegalArgumentException("Unsupported tile task implementation");
        return new State(kind,task.captureBaseState(),task.getTaskLocation(),task.getPlayerId());
    }
    /** Returns a shell so failed-task references can bind before the assignee controllers exist. */
    public static AbstractTileTask restore(State state, TaskIdentityAllocator ids, INavigationService navigation, IMapController map) {
        var saved=state.base();var p=state.location();short player=state.player();
        return switch(state.kind()) {
            case DIG -> new DigTileTask(ids,saved,navigation,map,p,player);
            case CLAIM_TILE -> new ClaimTileTask(ids,saved,navigation,map,p,player);
            case CLAIM_ROOM -> new ClaimRoomTask(ids,saved,navigation,map,p,player);
            case CLAIM_WALL -> new ClaimWallTileTask(ids,saved,navigation,map,p,player);
            case REPAIR_WALL -> new RepairWallTileTask(ids,saved,navigation,map,p,player);
            case OBJECTIVE_DIG -> new ObjectiveDigTileTask(ids,saved,navigation,map,p,player);
        };
    }
    public static byte[] encode(State state) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);enumeration(Kind.class).write(out,state.kind());
            var identity=state.base().identity();LONG.write(out,identity.id());LONG.write(out,identity.created().getEpochSecond());INT.write(out,identity.created().getNano());
            POINT.write(out,state.location());SHORT.write(out,state.player());out.writeInt(state.base().assignees().size());
            for(var entry:new TreeMap<>(state.base().assignees()).entrySet()){ENTITY_ID.write(out,entry.getKey());FLOAT.write(out,entry.getValue());}
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported tile task section");
            var kind=enumeration(Kind.class).read(in);long id=LONG.read(in);long seconds=LONG.read(in);int nano=INT.read(in);
            if(nano<0 || nano>=1_000_000_000)throw new IOException("Invalid task timestamp");
            var identity=new TaskIdentityAllocator.Identity(id,Instant.ofEpochSecond(seconds,nano));var point=POINT.read(in);short player=SHORT.read(in);
            int n=count(in.readInt(),65_536);var assignees=new TreeMap<com.simsilica.es.EntityId,Float>();
            for(int i=0;i<n;i++)if(assignees.put(ENTITY_ID.read(in),FLOAT.read(in))!=null)throw new IOException("Duplicate task assignee");
            if(in.read()!=-1)throw new IOException("Trailing tile task data");return new State(kind,new TaskBaseState(identity,assignees),point,player);
        } catch(IllegalArgumentException | NullPointerException | java.time.DateTimeException e){throw new IOException("Invalid tile task section",e);}
    }
    private TileTaskSnapshots() { }
}
