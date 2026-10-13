/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.objective.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import static toniarts.openkeeper.game.save.SaveValues.*;
import toniarts.openkeeper.game.save.TaskNodeReferences.Reference;

/** One identity graph shared by manager roots, objective edges and cached creature retry references. */
public final class TaskGraphSnapshots {
    public sealed interface Payload permits Tile, RoomWork, EntityWork, ObjectiveWork, Decorator { }
    public record Tile(TileTaskSnapshots.State state) implements Payload {public Tile {Objects.requireNonNull(state);}}
    public record RoomWork(RoomTaskSnapshots.State state) implements Payload {public RoomWork {Objects.requireNonNull(state);}}
    public record EntityWork(EntityTaskSnapshots.State state) implements Payload {public EntityWork {Objects.requireNonNull(state);}}
    public record ObjectiveWork(ObjectiveTaskSnapshots.State state) implements Payload {public ObjectiveWork {Objects.requireNonNull(state);}}
    public record Decorator(Reference wrapped, boolean workerParty) implements Payload {public Decorator {Objects.requireNonNull(wrapped);}}
    public record Node(Reference reference, Payload payload, List<Reference> queue) {
        public Node {Objects.requireNonNull(reference);Objects.requireNonNull(payload);queue=List.copyOf(queue);if(queue.size()>MAX_NODES)throw new IllegalArgumentException("Objective queue too large");}
    }
    public record State(TaskIdentityAllocator.State allocation, List<Reference> roots, List<Node> nodes, List<RoomState> removedRooms) {
        public State {
            Objects.requireNonNull(allocation);roots=List.copyOf(roots);nodes=List.copyOf(nodes);removedRooms=List.copyOf(removedRooms);
            validate(allocation,roots,nodes,removedRooms);
        }
    }
    public record Context(TaskIdentityAllocator ids, GameEntityData data, IKwdFile definition, INavigationService navigation,
            IMapController map, Function<EntityId,IRoomController> rooms, TaskManager manager, IGameWorldController world,
            IEntityPositionLookup positions, IObjectsController objects, Function<Short,IPlayerController> players, ILevelInfo level) { }
    private enum Kind { TILE, ROOM, ENTITY, OBJECTIVE, DECORATOR }
    private static final int MAGIC=0x4d4b5447,VERSION=1,MAX_BYTES=64*1024*1024,MAX_NODES=65536,MAX_EDGES=262144,MAX_DEPTH=256;
    private static TaskBaseState base(Payload payload) {return switch(payload){case Tile p->p.state().base();case RoomWork p->p.state().base();case EntityWork p->p.state().base();case ObjectiveWork p->p.state().base();case Decorator p->null;};}
    private static boolean objective(Node node) {return node.payload() instanceof ObjectiveWork || node.payload() instanceof Decorator;}
    private static List<Reference> edges(Node node) {
        if(node.payload() instanceof Decorator decorator){var all=new ArrayList<>(node.queue());all.add(decorator.wrapped());return all;}
        return node.queue();
    }
    private static Node resolve(List<Node> nodes,Reference ref) {
        if(ref.nodeId()>=nodes.size())throw new IllegalArgumentException("Missing task node");
        var node=nodes.get((int)ref.nodeId());if(!node.reference().equals(ref))throw new IllegalArgumentException("Task reference identity differs");return node;
    }
    private static void validate(TaskIdentityAllocator.State allocation,List<Reference> roots,List<Node> nodes,List<RoomState> rooms) {
        if(nodes.size()>MAX_NODES || roots.size()>MAX_NODES || rooms.size()>MAX_NODES)throw new IllegalArgumentException("Task graph too large");
        Set<Long> identities=new HashSet<>();Set<EntityId> roomReferences=new HashSet<>();int edgeCount=0;
        int[] incoming=new int[nodes.size()],depth=new int[nodes.size()];
        for(int i=0;i<nodes.size();i++) {
            var node=nodes.get(i);
            if(node.reference().nodeId()!=i || node.reference().taskId()>=allocation.nextId())throw new IllegalArgumentException("Invalid task allocation/node index");
            var base=base(node.payload());
            if(base!=null && (base.identity().id()!=node.reference().taskId() || !identities.add(base.identity().id())))throw new IllegalArgumentException("Task identity duplicated/mismatched");
            if(!objective(node) && !node.queue().isEmpty())throw new IllegalArgumentException("Queue on a non-objective task");
            for(var child:node.queue())if(!objective(resolve(nodes,child)))throw new IllegalArgumentException("Non-objective queue member");
            for(var edge:edges(node)){resolve(nodes,edge);incoming[(int)edge.nodeId()]++;if(++edgeCount>MAX_EDGES)throw new IllegalArgumentException("Too many task edges");}
            if(node.payload() instanceof RoomWork p)roomReferences.add(p.state().room());
            if(node.payload() instanceof EntityWork p && p.state().room()!=null)roomReferences.add(p.state().room());
        }
        var ready=new ArrayDeque<Integer>();for(int i=0;i<incoming.length;i++)if(incoming[i]==0)ready.add(i);int visited=0;
        while(!ready.isEmpty()) {
            int id=ready.removeFirst();visited++;
            for(var edge:edges(nodes.get(id))) {
                int child=(int)edge.nodeId();depth[child]=Math.max(depth[child],depth[id]+1);
                if(depth[child]>MAX_DEPTH)throw new IllegalArgumentException("Task graph too deep");
                if(--incoming[child]==0)ready.addLast(child);
            }
        }
        if(visited!=nodes.size())throw new IllegalArgumentException("Cyclic objective graph");
        var seen=new HashSet<Long>();var pending=new ArrayDeque<Reference>();
        for(var root:roots){resolve(nodes,root);if(!seen.add(root.nodeId()))throw new IllegalArgumentException("Duplicate graph root");pending.add(root);}
        while(!pending.isEmpty())for(var edge:edges(resolve(nodes,pending.removeFirst())))if(seen.add(edge.nodeId()))pending.addLast(edge);
        if(seen.size()!=nodes.size())throw new IllegalArgumentException("Unreachable task node");
        var roomIds=new HashSet<EntityId>();
        for(var room:rooms)if(!roomIds.add(room.entityId()) || !roomReferences.contains(room.entityId()))throw new IllegalArgumentException("Duplicate/unreferenced removed room");
    }
    /** Called only under the simulation capture gate, once per fresh capture index.
     * Capture live creature caches into that index first; old graph nodes are not roots. */
    public static State capture(TaskNodeReferences.Capture references, Collection<Task> roots, TaskIdentityAllocator.State allocation) {
        roots.forEach(references::reference);
        var rootRefs=references.nodes().stream().map(references::reference).toList();
        var nodes=new ArrayList<Node>();var removed=new TreeMap<EntityId,IRoomController>();
        for(int i=0;i<references.size();i++) {
            Task task=references.node(i);Payload payload;
            if(TileTaskSnapshots.supports(task))payload=new Tile(TileTaskSnapshots.capture((AbstractTileTask)task));
            else if(RoomTaskSnapshots.supports(task))payload=new RoomWork(RoomTaskSnapshots.capture((AbstractRoomTask)task));
            else if(EntityTaskSnapshots.supports(task))payload=new EntityWork(EntityTaskSnapshots.capture((AbstractTask)task));
            else if(ObjectiveTaskSnapshots.supports(task))payload=new ObjectiveWork(ObjectiveTaskSnapshots.capture((AbstractObjectiveTask)task));
            else if(task.getClass()==ObjectiveTaskDecorator.class){var decorator=(ObjectiveTaskDecorator)task;payload=new Decorator(references.reference(decorator.getWrappedTask()),decorator.isWorkerPartyTask());}
            else throw new IllegalArgumentException("Unsupported task graph implementation: "+task.getClass().getName());
            var queue=task instanceof ObjectiveTask objective ? objective.getTaskQueue().stream().map(references::reference).toList() : List.<Reference>of();
            nodes.add(new Node(references.reference(task),payload,queue));
            if(task instanceof AbstractRoomTask roomTask && roomTask.getRoom().isRemoved()) {
                var room=roomTask.getRoom();var old=removed.putIfAbsent(room.getEntityId(),room);
                if(old!=null && old!=room)throw new IllegalArgumentException("Aliased removed room");
            }
        }
        return new State(allocation,rootRefs,nodes,removed.values().stream().map(room->((AbstractRoomController)room).captureState()).toList());
    }
    /** Owns strong references while cyclic creature edges and assignees are still being reconstructed. */
    public static final class Restored {
        private final State state;private final TaskManager manager;private final List<Task> nodes;private final TaskNodeReferences.Bindings bindings;private boolean bound;
        private Restored(State state,List<Task> nodes,TaskManager manager) {
            this.manager=manager;this.state=state;this.nodes=List.copyOf(nodes);var indexed=new HashMap<Long,Task>();
            for(int i=0;i<nodes.size();i++)indexed.put((long)i,nodes.get(i));bindings=new TaskNodeReferences.Bindings(indexed);
        }
        public void requireOwner(TaskManager owner){if(manager!=owner)throw new IllegalArgumentException("Task graph belongs to a different manager");}
        public void requireBound(){if(!bound)throw new IllegalStateException("Task graph creatures are not bound");}
        public TaskNodeReferences.Bindings bindings(){return bindings;}
        public List<Task> nodes(){return nodes;}
        public List<Task> roots(){return state.roots().stream().map(bindings::resolve).toList();}
        public void bindCreatures(Function<EntityId,ICreatureController> creatures) {
            if(bound)throw new IllegalStateException("Task graph creatures already bound");
            var ids=new TreeSet<EntityId>();
            for(var node:state.nodes()){var base=base(node.payload());if(base!=null)ids.addAll(base.assignees().keySet());}
            for(var task:nodes)if(task instanceof CreatureTargetTask target)ids.add(target.creatureTarget().id());
            var resolved=new HashMap<EntityId,ICreatureController>();
            for(var id:ids){var creature=creatures.apply(id);if(creature==null || !id.equals(creature.getEntityId()))throw new IllegalArgumentException("Missing/mismatched graph creature");resolved.put(id,creature);}
            for(var task:nodes)if(task instanceof CreatureTargetTask target)target.creatureTarget().bind(resolved::get);
            for(var task:nodes)if(task instanceof AbstractTask concrete)concrete.bindRestoredAssignees(resolved::get);
            bound=true;
        }
    }
    /** Removed-room dependencies must already be bound into the shared room resolver. */
    public static Restored restoreShells(State state,Context c) {
        if(!state.allocation().equals(c.ids().captureState()))throw new IllegalArgumentException("Task allocator differs from graph");
        var tasks=new ArrayList<Task>(state.nodes().size());
        for(var node:state.nodes())tasks.add(switch(node.payload()) {
            case Tile p->TileTaskSnapshots.restore(p.state(),c.ids(),c.navigation(),c.map());
            case RoomWork p->RoomTaskSnapshots.restore(p.state(),c.ids(),c.navigation(),c.map(),c.rooms(),c.manager(),c.world(),c.data(),c.players(),c.objects());
            case EntityWork p->EntityTaskSnapshots.restore(p.state(),c.ids(),c.navigation(),c.map(),c.rooms(),c.manager(),c.positions(),c.data(),c.definition(),c.objects());
            case ObjectiveWork p->ObjectiveTaskSnapshots.restore(p.state(),c.ids(),c.navigation(),c.map(),c.level(),c.data());
            case Decorator p->ObjectiveTaskDecorator.restoreShell(node.reference().taskId(),p.workerParty());
        });
        for(int i=0;i<tasks.size();i++) {
            var node=state.nodes().get(i);Task task=tasks.get(i);
            if(node.payload() instanceof Decorator p)((ObjectiveTaskDecorator)task).bindRestoredTask(tasks.get((int)p.wrapped().nodeId()));
            if(task instanceof ObjectiveTask objective)for(var ref:node.queue())objective.getTaskQueue().add((ObjectiveTask)tasks.get((int)ref.nodeId()));
        }
        return new Restored(state,tasks,c.manager());
    }
    public static byte[] encode(State state)throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(new OutputStream(){int size;public void write(int b)throws IOException{reserve(1);bytes.write(b);}public void write(byte[] b,int off,int len)throws IOException{reserve(len);bytes.write(b,off,len);}void reserve(int n)throws IOException{if(n>MAX_BYTES-size)throw new IOException("Task graph exceeds size limit");size+=n;}})) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(state.allocation().nextId());out.writeInt(state.nodes().size());
            for(var node:state.nodes()) {
                writeRef(out,node.reference());
                Kind kind=switch(node.payload()){case Tile p->Kind.TILE;case RoomWork p->Kind.ROOM;case EntityWork p->Kind.ENTITY;case ObjectiveWork p->Kind.OBJECTIVE;case Decorator p->Kind.DECORATOR;};
                enumeration(Kind.class).write(out,kind);
                if(node.payload() instanceof Decorator p){writeRef(out,p.wrapped());out.writeBoolean(p.workerParty());}
                else writeBytes(out,switch(node.payload()){case Tile p->TileTaskSnapshots.encode(p.state());case RoomWork p->RoomTaskSnapshots.encode(p.state());case EntityWork p->EntityTaskSnapshots.encode(p.state());case ObjectiveWork p->ObjectiveTaskSnapshots.encode(p.state());case Decorator p->throw new AssertionError();});
                out.writeInt(node.queue().size());for(var ref:node.queue())writeRef(out,ref);
            }
            out.writeInt(state.roots().size());for(var ref:state.roots())writeRef(out,ref);
            writeBytes(out,RoomSnapshots.encode(state.removedRooms()));
        }
        return bytes.toByteArray();
    }
    public static State decode(byte[] bytes)throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unsupported task graph");
            var allocation=new TaskIdentityAllocator.State(in.readLong());int n=count(in.readInt(),MAX_NODES);var nodes=new ArrayList<Node>(n);int edgeCount=0;
            for(int i=0;i<n;i++) {
                var ref=readRef(in);var kind=enumeration(Kind.class).read(in);
                Payload payload=switch(kind){case TILE->new Tile(TileTaskSnapshots.decode(readBytes(in,1024*1024)));case ROOM->new RoomWork(RoomTaskSnapshots.decode(readBytes(in,1024*1024)));case ENTITY->new EntityWork(EntityTaskSnapshots.decode(readBytes(in,1024*1024)));case OBJECTIVE->new ObjectiveWork(ObjectiveTaskSnapshots.decode(readBytes(in,1024*1024)));case DECORATOR->new Decorator(readRef(in),BOOLEAN.read(in));};
                int q=count(in.readInt(),MAX_NODES);edgeCount+=q+(kind==Kind.DECORATOR?1:0);count(edgeCount,MAX_EDGES);
                var queue=new ArrayList<Reference>(q);for(int j=0;j<q;j++)queue.add(readRef(in));nodes.add(new Node(ref,payload,queue));
            }
            int r=count(in.readInt(),MAX_NODES);var roots=new ArrayList<Reference>(r);for(int i=0;i<r;i++)roots.add(readRef(in));
            var rooms=RoomSnapshots.decode(readBytes(in,MAX_BYTES));if(in.read()!=-1)throw new IOException("Trailing task graph data");
            return new State(allocation,roots,nodes,rooms);
        }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid task graph",e);}
    }
    private static void writeRef(DataOutput out,Reference ref)throws IOException{out.writeLong(ref.nodeId());out.writeLong(ref.taskId());}
    private static Reference readRef(DataInput in)throws IOException{return new Reference(in.readLong(),in.readLong());}
    private static void writeBytes(DataOutput out,byte[] bytes)throws IOException{out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] readBytes(DataInput in,int max)throws IOException{var bytes=new byte[count(in.readInt(),max)];in.readFully(bytes);return bytes;}
    private TaskGraphSnapshots() { }
}
