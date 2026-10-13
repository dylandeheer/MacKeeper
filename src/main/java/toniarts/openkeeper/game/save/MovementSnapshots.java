/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.navigation.steering.*;
import toniarts.openkeeper.game.navigation.steering.EntitySteeringFactory.Plan;
import toniarts.openkeeper.game.navigation.steering.EntitySteeringFactory.Waypoint;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Active steering dynamics, frozen route geometry and unconsumed query events. */
public final class MovementSnapshots {
    private static final int MAGIC = 0x4d4b4d56, VERSION = 1, MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTITIES = 65536, MAX_WAYPOINTS = 1048576;

    public record Body(EntityId id, int targetX, int targetY, Plan plan, Waypoint position, Waypoint velocity,
            float orientation, float angularVelocity, float linearSpeed, float linearAcceleration,
            float angularSpeed, float angularAcceleration, float threshold, float radius, boolean tagged) {
        public Body {
            Objects.requireNonNull(id);Objects.requireNonNull(plan);Objects.requireNonNull(position);Objects.requireNonNull(velocity);
            if (id.getId() < 0 || !plan.active() || !Float.isFinite(orientation) || !Float.isFinite(angularVelocity))
                throw new IllegalArgumentException("Invalid movement body");
            for (float value : new float[]{linearSpeed, linearAcceleration, angularSpeed, angularAcceleration, threshold, radius})
                if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid movement limit");
        }
        public static Body capture(ISteerableEntity entity, int x, int y, Plan plan) {
            return new Body(entity.getEntityId(), x, y, plan,
                    new Waypoint(entity.getPosition().x, entity.getPosition().y),
                    new Waypoint(entity.getLinearVelocity().x, entity.getLinearVelocity().y), entity.getOrientation(),
                    entity.getAngularVelocity(), entity.getMaxLinearSpeed(), entity.getMaxLinearAcceleration(),
                    entity.getMaxAngularSpeed(), entity.getMaxAngularAcceleration(), entity.getZeroLinearSpeedThreshold(),
                    entity.getBoundingRadius(), entity.isTagged());
        }
        public SteerableEntity restore() {
            var entity = new SteerableEntity(id, linearSpeed, radius, position.x(), position.y(), orientation);
            entity.getLinearVelocity().set(velocity.x(), velocity.y());entity.setAngularVelocity(angularVelocity);
            entity.setMaxLinearAcceleration(linearAcceleration);entity.setMaxAngularSpeed(angularSpeed);
            entity.setMaxAngularAcceleration(angularAcceleration);entity.setZeroLinearSpeedThreshold(threshold);entity.setTagged(tagged);
            return entity;
        }
    }

    public record State(List<EntityId> observed, List<EntityId> dirty, List<Body> bodies) {
        public State {
            observed = List.copyOf(observed);dirty = List.copyOf(dirty);bodies = List.copyOf(bodies);
            ordered(observed);ordered(dirty);ordered(bodies.stream().map(Body::id).toList());
            var known = new HashSet<>(observed);
            if (!known.containsAll(dirty) || bodies.stream().anyMatch(body -> !known.contains(body.id())))
                throw new IllegalArgumentException("Unknown movement observation");
            var handled = new HashSet<>(dirty);bodies.forEach(body -> handled.add(body.id()));
            if (!handled.containsAll(observed)) throw new IllegalArgumentException("Movement observation has no body or pending change");
            if (bodies.stream().mapToLong(body -> body.plan().path().size()).sum() > MAX_WAYPOINTS)
                throw new IllegalArgumentException("Too many movement waypoints");
        }
    }
    private static void ordered(List<EntityId> ids) {
        if (ids.size() > MAX_ENTITIES) throw new IllegalArgumentException("Too many movement entities");
        long previous = -1;
        for (var id : ids) {
            if (id.getId() <= previous) throw new IllegalArgumentException("Invalid movement entity order");
            previous = id.getId();
        }
    }
    private static void waypoint(DataOutput out, Waypoint value) throws IOException {FLOAT.write(out,value.x());FLOAT.write(out,value.y());}
    private static Waypoint waypoint(DataInput in) throws IOException {return new Waypoint(FLOAT.read(in),FLOAT.read(in));}
    private static List<EntityId> ids(DataInput in) throws IOException {
        int size = count(in.readInt(), MAX_ENTITIES);var ids = new ArrayList<EntityId>();
        for (int i=0;i<size;i++) ids.add(ENTITY_ID.read(in));return ids;
    }
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);list(ENTITY_ID).write(out,state.observed());list(ENTITY_ID).write(out,state.dirty());
            out.writeInt(state.bodies().size());
            for (var body : state.bodies()) {
                ENTITY_ID.write(out,body.id());out.writeInt(body.targetX());out.writeInt(body.targetY());
                out.writeInt(body.plan().path().size());for (var point : body.plan().path()) waypoint(out,point);
                nullable(FLOAT).write(out,body.plan().facing());waypoint(out,body.position());waypoint(out,body.velocity());
                for (float value : new float[]{body.orientation(),body.angularVelocity(),body.linearSpeed(),body.linearAcceleration(),
                        body.angularSpeed(),body.angularAcceleration(),body.threshold(),body.radius()}) FLOAT.write(out,value);
                BOOLEAN.write(out,body.tagged());
            }
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported movement section");
            var observed = ids(in);var dirty = ids(in);int n = count(in.readInt(),MAX_ENTITIES), total = 0;
            var bodies = new ArrayList<Body>();
            for (int i=0;i<n;i++) {
                var id = ENTITY_ID.read(in);int x = in.readInt(), y = in.readInt(), points = count(in.readInt(),65536);
                total += points;count(total,MAX_WAYPOINTS);var path = new ArrayList<Waypoint>();
                for (int p=0;p<points;p++) path.add(waypoint(in));
                var plan = new Plan(path,nullable(FLOAT).read(in));var position = waypoint(in);var velocity = waypoint(in);
                bodies.add(new Body(id,x,y,plan,position,velocity,FLOAT.read(in),FLOAT.read(in),FLOAT.read(in),FLOAT.read(in),
                        FLOAT.read(in),FLOAT.read(in),FLOAT.read(in),FLOAT.read(in),BOOLEAN.read(in)));
            }
            var state = new State(observed,dirty,bodies);
            if (in.read() != -1) throw new IOException("Trailing movement data");return state;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid movement section",e);}
    }
    private MovementSnapshots() { }
}
