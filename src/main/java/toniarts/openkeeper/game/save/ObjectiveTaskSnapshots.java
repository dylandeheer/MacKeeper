/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.time.Instant;
import java.util.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.TaskBaseState;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.objective.*;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Objective values only. Queue/wrapper edges belong to the shared task graph. */
public final class ObjectiveTaskSnapshots {
    public enum Kind { GO_TO, WAYPOINT, KILL_PLAYER }
    public record State(Kind kind, TaskBaseState base, Point location, short player, boolean goToExecuted,
            boolean waypointExecuted, int actionPoint, short targetPlayer, EntityId creature) {
        public State {
            Objects.requireNonNull(kind);Objects.requireNonNull(base);
            if (player < 0 || player > 255 || location != null && (location.x < 0 || location.y < 0)) throw new IllegalArgumentException("Invalid objective target");
            if (kind == Kind.KILL_PLAYER) {
                if (creature == null || creature.getId() < 0 || targetPlayer < 0 || targetPlayer > 255 || goToExecuted || waypointExecuted || actionPoint != 0)
                    throw new IllegalArgumentException("Invalid kill-player values");
            } else {
                if (location == null || creature != null || targetPlayer != 0) throw new IllegalArgumentException("Invalid go-to values");
                if (kind == Kind.GO_TO && (waypointExecuted || actionPoint != 0)) throw new IllegalArgumentException("Unexpected waypoint values");
                if (kind == Kind.WAYPOINT && (actionPoint < 0 || actionPoint > 65535)) throw new IllegalArgumentException("Invalid action point ID");
            }
        }
    }
    private static final Set<Class<?>> SUPPORTED = Set.of(GoToTask.class, SendToActionPoint.class, KillPlayer.class);
    private static final int MAGIC = 0x4d4b4f54, VERSION = 1, MAX_BYTES = 1024 * 1024;
    public static boolean supports(Task task) {return SUPPORTED.contains(task.getClass());}
    public static State capture(AbstractObjectiveTask task) {
        if (task.getClass() == GoToTask.class) return new State(Kind.GO_TO, task.captureBaseState(), task.captureTileLocation(), task.getPlayerId(), ((GoToTask)task).hasExecutedGoTo(), false, 0, (short)0, null);
        if (task instanceof SendToActionPoint point) return new State(Kind.WAYPOINT, task.captureBaseState(), task.captureTileLocation(), task.getPlayerId(), point.hasExecutedGoTo(), point.hasExecutedWaypoint(), point.getActionPoint().getId(), (short)0, null);
        if (task instanceof KillPlayer kill) return new State(Kind.KILL_PLAYER, task.captureBaseState(), task.captureTileLocation(), task.getPlayerId(), false, false, 0, kill.getTargetPlayerId(), kill.creatureTarget().get().getEntityId());
        throw new IllegalArgumentException("Unsupported objective implementation");
    }
    public static AbstractObjectiveTask restore(State state, TaskIdentityAllocator ids, INavigationService navigation,
            IMapController map, ILevelInfo level, GameEntityData data) {
        var base=state.base();var point=state.location();short player=state.player();
        return switch(state.kind()) {
            case GO_TO -> new GoToTask(ids,base,navigation,map,point,player,state.goToExecuted());
            case WAYPOINT -> {
                var action=level.getActionPoint(state.actionPoint());
                if(action==null || action.getId()!=state.actionPoint())throw new IllegalArgumentException("Missing/mismatched action point");
                yield new SendToActionPoint(ids,base,navigation,map,point,player,action,state.goToExecuted(),state.waypointExecuted());
            }
            case KILL_PLAYER -> {
                if(!data.wasAllocated(state.creature()))throw new IllegalArgumentException("Unallocated objective creature");
                yield new KillPlayer(ids,base,navigation,map,point,player,level,state.targetPlayer(),state.creature());
            }
        };
    }
    public static byte[] encode(State state) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);enumeration(Kind.class).write(out,state.kind());
            var id=state.base().identity();out.writeLong(id.id());out.writeLong(id.created().getEpochSecond());out.writeInt(id.created().getNano());
            nullable(POINT).write(out,state.location());out.writeShort(state.player());out.writeBoolean(state.goToExecuted());out.writeBoolean(state.waypointExecuted());
            out.writeInt(state.actionPoint());out.writeShort(state.targetPlayer());nullable(ENTITY_ID).write(out,state.creature());
            out.writeInt(state.base().assignees().size());
            for(var entry:new TreeMap<>(state.base().assignees()).entrySet()){ENTITY_ID.write(out,entry.getKey());FLOAT.write(out,entry.getValue());}
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported objective section");
            var kind=enumeration(Kind.class).read(in);long id=in.readLong(),seconds=in.readLong();int nano=in.readInt();
            if(nano<0 || nano>=1_000_000_000)throw new IOException("Invalid task timestamp");
            var identity=new TaskIdentityAllocator.Identity(id,Instant.ofEpochSecond(seconds,nano));
            var point=nullable(POINT).read(in);short player=in.readShort();boolean go=BOOLEAN.read(in),waypoint=BOOLEAN.read(in);
            int action=in.readInt();short target=in.readShort();var creature=nullable(ENTITY_ID).read(in);
            int n=count(in.readInt(),65536);var assignees=new TreeMap<EntityId,Float>();
            for(int i=0;i<n;i++)if(assignees.put(ENTITY_ID.read(in),FLOAT.read(in))!=null)throw new IOException("Duplicate task assignee");
            if(in.read()!=-1)throw new IOException("Trailing objective data");
            return new State(kind,new TaskBaseState(identity,assignees),point,player,go,waypoint,action,target,creature);
        }catch(IllegalArgumentException|NullPointerException|java.time.DateTimeException e){throw new IOException("Invalid objective section",e);}
    }
    private ObjectiveTaskSnapshots() { }
}
