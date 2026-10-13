/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.controller.creature.WorkContinuation;
import toniarts.openkeeper.game.save.TaskNodeReferences.Reference;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Task ownership and last-observed query membership, separate from executable node values. */
public final class TaskManagerSnapshots {
    public record Query(List<EntityId> observed, List<EntityId> dirty) {
        public Query {
            observed=ids(observed);dirty=ids(dirty);
            if(!new HashSet<>(observed).containsAll(dirty))throw new IllegalArgumentException("Dirty task query member was not observed");
        }
    }
    public record Queue(short player, List<Reference> tasks) {
        public Queue {
            if(player<0 || player>255)throw new IllegalArgumentException("Invalid task queue player");
            tasks=List.copyOf(tasks);unique(tasks.stream().map(Reference::nodeId).toList());
        }
    }
    public record Reservation(EntityId room, Point point, Reference task) {
        public Reservation {entity(room);Objects.requireNonNull(point);Objects.requireNonNull(task);if(point.x<0 || point.y<0)throw new IllegalArgumentException("Invalid reservation tile");}
    }
    public record Worker(EntityId creature, WorkContinuation continuation) {
        public Worker {entity(creature);Objects.requireNonNull(continuation);}
    }
    public record State(List<Queue> queues, Map<Long,Reference> registered, Map<EntityId,Long> entityTasks,
            List<Reservation> reservations, List<Worker> workers, Query tasks, Query unconscious, Query corpses, Query freeObjects) {
        public State {
            queues=List.copyOf(queues);registered=Map.copyOf(registered);entityTasks=Map.copyOf(entityTasks);
            reservations=List.copyOf(reservations);workers=List.copyOf(workers);
            if(queues.size()>256)throw new IllegalArgumentException("Too many task queues");
            unique(queues.stream().map(Queue::player).toList());
            unique(reservations.stream().map(r->List.of(r.room(),r.point())).toList());
            unique(workers.stream().map(Worker::creature).toList());
            limit(registered.size());limit(entityTasks.size());limit(reservations.size());limit(workers.size());
            long edges=registered.size()+reservations.size();for(var queue:queues)edges+=queue.tasks().size();
            if(edges>262144)throw new IllegalArgumentException("Too many task manager references");
            registered.forEach((id,ref)->{if(id<0 || id!=ref.taskId())throw new IllegalArgumentException("Invalid registered task ID");});
            entityTasks.forEach((id,task)->{entity(id);if(task<0)throw new IllegalArgumentException("Invalid observed task ID");});
            Objects.requireNonNull(tasks);Objects.requireNonNull(unconscious);Objects.requireNonNull(corpses);Objects.requireNonNull(freeObjects);
            if(!new HashSet<>(tasks.observed()).equals(entityTasks.keySet()))throw new IllegalArgumentException("Task query/index membership differs");
        }
    }
    private static final int MAGIC=0x4d4b544d,VERSION=1,MAX_BYTES=16*1024*1024;
    private static void entity(EntityId id){if(id==null || id.getId()<0)throw new IllegalArgumentException("Invalid entity reference");}
    private static void limit(int size){if(size>65536)throw new IllegalArgumentException("Task manager collection too large");}
    private static void unique(List<?> list){limit(list.size());if(new HashSet<>(list).size()!=list.size())throw new IllegalArgumentException("Duplicate task manager key");}
    private static List<EntityId> ids(List<EntityId> values){var copy=List.copyOf(values);unique(copy);copy.forEach(TaskManagerSnapshots::entity);return copy;}
    public static byte[] encode(State state)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(state.queues().size());
            for(var queue:state.queues()){out.writeShort(queue.player());out.writeInt(queue.tasks().size());for(var ref:queue.tasks())writeRef(out,ref);}
            out.writeInt(state.registered().size());for(var entry:new TreeMap<>(state.registered()).entrySet()){out.writeLong(entry.getKey());writeRef(out,entry.getValue());}
            out.writeInt(state.entityTasks().size());for(var entry:new TreeMap<>(state.entityTasks()).entrySet()){ENTITY_ID.write(out,entry.getKey());out.writeLong(entry.getValue());}
            out.writeInt(state.reservations().size());for(var r:state.reservations()){ENTITY_ID.write(out,r.room());POINT.write(out,r.point());writeRef(out,r.task());}
            out.writeInt(state.workers().size());for(var worker:state.workers()){ENTITY_ID.write(out,worker.creature());enumeration(WorkContinuation.class).write(out,worker.continuation());}
            for(var query:List.of(state.tasks(),state.unconscious(),state.corpses(),state.freeObjects())){list(ENTITY_ID).write(out,query.observed());list(ENTITY_ID).write(out,query.dirty());}
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes)throws IOException {
        count(bytes.length,MAX_BYTES);try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported task manager section");
            int n=count(in.readInt(),256),edges=0;var queues=new ArrayList<Queue>();
            for(int i=0;i<n;i++){short player=in.readShort();int k=count(in.readInt(),65536);edges+=k;count(edges,262144);var refs=new ArrayList<Reference>();for(int j=0;j<k;j++)refs.add(readRef(in));queues.add(new Queue(player,refs));}
            n=count(in.readInt(),65536);edges+=n;count(edges,262144);var registered=new TreeMap<Long,Reference>();
            for(int i=0;i<n;i++)if(registered.put(in.readLong(),readRef(in))!=null)throw new IOException("Duplicate registered task");
            n=count(in.readInt(),65536);var entityTasks=new TreeMap<EntityId,Long>();for(int i=0;i<n;i++)if(entityTasks.put(ENTITY_ID.read(in),in.readLong())!=null)throw new IOException("Duplicate observed task entity");
            n=count(in.readInt(),65536);edges+=n;count(edges,262144);var reservations=new ArrayList<Reservation>();for(int i=0;i<n;i++)reservations.add(new Reservation(ENTITY_ID.read(in),POINT.read(in),readRef(in)));
            n=count(in.readInt(),65536);var workers=new ArrayList<Worker>();for(int i=0;i<n;i++)workers.add(new Worker(ENTITY_ID.read(in),enumeration(WorkContinuation.class).read(in)));
            var state=new State(queues,registered,entityTasks,reservations,workers,query(in),query(in),query(in),query(in));
            if(in.read()!=-1)throw new IOException("Trailing task manager data");return state;
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid task manager section",e);}
    }
    private static Query query(DataInput in)throws IOException{return new Query(list(ENTITY_ID).read(in),list(ENTITY_ID).read(in));}
    private static void writeRef(DataOutput out,Reference ref)throws IOException{out.writeLong(ref.nodeId());out.writeLong(ref.taskId());}
    private static Reference readRef(DataInput in)throws IOException{return new Reference(in.readLong(),in.readLong());}
    private TaskManagerSnapshots() { }
}
