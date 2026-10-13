/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.task.TaskIdentityAllocator;
import toniarts.openkeeper.tools.convert.map.MapSource;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable.MiscType;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Bounded, explicit server archive with integrity checking and atomic file replacement. */
public final class SimulationArchives {
    private static final int MAGIC = 0x4d4b5341; // MKSA
    private static final int VERSION = 2;
    static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;
    static final int MAX_PART_BYTES = 128 * 1024 * 1024;
    static final int HEADER_BYTES = 12, DIGEST_BYTES = 32;
    private static final int MAX_FILE_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES + DIGEST_BYTES;

    public static byte[] encode(SimulationArchive archive) throws IOException {
        var payload = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new BoundedOutput(payload, MAX_PAYLOAD_BYTES))) {
            var definition = archive.definition();var source = definition.source();
            TEXT.write(out, source.mapPath());out.writeInt(source.files().size());
            for (var file : source.files()) {TEXT.write(out, file.path());out.writeLong(file.size());TEXT.write(out, file.sha256());}
            out.writeInt(definition.ticksPerSecond());out.writeInt(definition.settings().size());
            for (var setting : definition.settings()) {
                enumeration(MiscType.class).write(out, setting.type());FLOAT.write(out, setting.value());
                out.writeInt(setting.unknown1());out.writeInt(setting.unknown2());
            }
            var state = archive.simulation();var systems = state.systems();
            writePart(out, EntitySnapshots.encode(state.entities()));
            writeList(out, state.keepers(), 256, KeeperSnapshots::encode);
            writeList(out, state.players(), 256, PlayerControlSnapshots::encode);
            writePart(out, RoomSnapshots.encode(state.rooms()));
            writePart(out, MapControlSnapshots.encode(state.flashes()));
            writePart(out, LevelSnapshots.encode(state.level()));
            writePart(out, RandomSnapshots.encode(state.random()));
            writePart(out, ResultSnapshots.encode(state.results()));
            for (var phase : state.loops()) {
                out.writeLong(phase.intervalNanos());out.writeLong(phase.remainderNanos());
                out.writeLong(phase.pendingNanos());BOOLEAN.write(out, phase.paused());
            }
            out.writeInt(state.triggers().size());
            for (var entry : new TreeMap<>(state.triggers()).entrySet()) {
                enumeration(TriggerRegistrySnapshots.Kind.class).write(out, entry.getKey());
                writePart(out, TriggerRegistrySnapshots.encode(entry.getValue()));
            }
            writePart(out, CreatureIndexSnapshots.encode(systems.creatures()));
            writePart(out, SpellbookIndexSnapshots.encode(systems.spellbooks()));
            writePart(out, ChickenSnapshots.encode(systems.chickens()));
            writeList(out, systems.creatureControllers(), 65536, CreatureControlSnapshots::encode);
            writePart(out, CreatureSystemSnapshots.encode(systems.creatureAi()));
            writePart(out, PartySnapshots.encode(systems.parties()));
            writeList(out, systems.tileTasks(), 65536, TileTaskSnapshots::encode);
            out.writeLong(systems.taskAllocation().nextId());
            writeList(out, systems.roomTasks().tasks(), 65_536, RoomTaskSnapshots::encode);
            writePart(out, RoomSnapshots.encode(systems.roomTasks().removedRooms()));
            writeList(out, systems.entityTasks().tasks(), 65_536, EntityTaskSnapshots::encode);
            writePart(out, RoomSnapshots.encode(systems.entityTasks().removedRooms()));
            writePart(out, TaskGraphSnapshots.encode(systems.taskGraph()));
            writePart(out, TaskManagerSnapshots.encode(systems.taskManager()));
            writePart(out, ManaSnapshots.encode(systems.mana()));
            writePart(out, ExperienceSnapshots.encode(systems.experience()));
            writePart(out, KeeperEconomySnapshots.encode(systems.economy()));
            writePart(out, CreatureSpawnSnapshots.encode(systems.recruitment()));
            writePart(out, ChickenSpawnSnapshots.encode(systems.chickenSpawning()));
            writePart(out, LifetimeSnapshots.encode(systems.lifetimes()));
            writePart(out, SlapSnapshots.encode(systems.slaps()));
            writePart(out, HealthSnapshots.encode(systems.health()));
            writePart(out, AnimationSnapshots.encode(systems.animation()));
            writePart(out, CreatureViewSnapshots.encode(systems.creatureView()));
            writePart(out, MovementSnapshots.encode(systems.movement()));
            writePart(out, PositionSnapshots.encode(systems.positions()));
        }
        var bytes = new ByteArrayOutputStream(HEADER_BYTES + payload.size() + DIGEST_BYTES);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(payload.size());payload.writeTo(out);
            out.write(hash(bytes.toByteArray()));
        }
        return bytes.toByteArray();
    }

    public static SimulationArchive decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_FILE_BYTES);
        if (bytes.length < HEADER_BYTES + DIGEST_BYTES) throw new IOException("Truncated simulation archive");
        try (var envelope = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (envelope.readInt() != MAGIC || envelope.readInt() != VERSION) throw new IOException("Unsupported simulation archive");
            int size = count(envelope.readInt(), MAX_PAYLOAD_BYTES);
            if (bytes.length != HEADER_BYTES + size + DIGEST_BYTES) throw new IOException("Archive length differs");
            var digest = new byte[DIGEST_BYTES];System.arraycopy(bytes, HEADER_BYTES + size, digest, 0, DIGEST_BYTES);
            var expected = sha256();expected.update(bytes, 0, HEADER_BYTES + size);
            if (!MessageDigest.isEqual(digest, expected.digest())) throw new IOException("Simulation archive checksum differs");
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes, HEADER_BYTES, size))) {
                return readPayload(in);
            }
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid simulation archive", failure);
        }
    }

    private static SimulationArchive readPayload(DataInputStream in) throws IOException {
            String mapPath = TEXT.read(in);int fileCount = count(in.readInt(), MapSource.MAX_FILES);
            var files = new ArrayList<MapSource.File>();
            for (int i = 0; i < fileCount; i++) files.add(new MapSource.File(TEXT.read(in), in.readLong(), TEXT.read(in)));
            int ticks = in.readInt(), settingCount = count(in.readInt(), MiscType.values().length);
            var settings = new ArrayList<SimulationArchive.Setting>();
            for (int i = 0; i < settingCount; i++) settings.add(new SimulationArchive.Setting(
                    enumeration(MiscType.class).read(in), FLOAT.read(in), in.readInt(), in.readInt()));
            var definition = new SimulationArchive.Definition(new MapSource(mapPath, files), ticks, settings);
            var entities = EntitySnapshots.decode(readPart(in));
            var keepers = readList(in, 256, KeeperSnapshots::decode);
            var players = readList(in, 256, PlayerControlSnapshots::decode);
            var rooms = RoomSnapshots.decode(readPart(in));
            var flashes = MapControlSnapshots.decode(readPart(in));
            var level = LevelSnapshots.decode(readPart(in));
            var random = RandomSnapshots.decode(readPart(in));
            var results = ResultSnapshots.decode(readPart(in));
            var loops = new ArrayList<toniarts.openkeeper.utils.GameLoopClock.State>();
            for (int i = 0; i < 3; i++) loops.add(new toniarts.openkeeper.utils.GameLoopClock.State(
                    in.readLong(), in.readLong(), in.readLong(), BOOLEAN.read(in)));
            int triggerCount = count(in.readInt(), TriggerRegistrySnapshots.Kind.values().length);
            var triggers = new EnumMap<TriggerRegistrySnapshots.Kind, TriggerRegistrySnapshots.State>(TriggerRegistrySnapshots.Kind.class);
            for (int i = 0; i < triggerCount; i++) {
                var kind = enumeration(TriggerRegistrySnapshots.Kind.class).read(in);
                var state = TriggerRegistrySnapshots.decode(readPart(in));
                if (state.kind() != kind || triggers.put(kind, state) != null) throw new IOException("Duplicate or mismatched trigger registry");
            }
            if (triggers.size() != TriggerRegistrySnapshots.Kind.values().length) throw new IOException("Missing trigger registry");
            var creatures = CreatureIndexSnapshots.decode(readPart(in));
            var spellbooks = SpellbookIndexSnapshots.decode(readPart(in));
            var chickens = ChickenSnapshots.decode(readPart(in));
            var creatureControllers = readList(in, 65536, CreatureControlSnapshots::decode);
            var creatureAi = CreatureSystemSnapshots.decode(readPart(in));
            var parties = PartySnapshots.decode(readPart(in));
            var tileTasks = readList(in, 65536, TileTaskSnapshots::decode);
            var taskAllocation = new TaskIdentityAllocator.State(in.readLong());
            var roomTasks = new RoomTaskSnapshots.Capture(readList(in, 65_536, RoomTaskSnapshots::decode), RoomSnapshots.decode(readPart(in)));
            var entityTasks = new EntityTaskSnapshots.Capture(readList(in, 65_536, EntityTaskSnapshots::decode), RoomSnapshots.decode(readPart(in)));
            var taskGraph = TaskGraphSnapshots.decode(readPart(in));
            var taskManager = TaskManagerSnapshots.decode(readPart(in));
            var mana = ManaSnapshots.decode(readPart(in));
            var experience = ExperienceSnapshots.decode(readPart(in));
            var economy = KeeperEconomySnapshots.decode(readPart(in));
            var recruitment = CreatureSpawnSnapshots.decode(readPart(in));
            var chickenSpawning = ChickenSpawnSnapshots.decode(readPart(in));
            var lifetimes = LifetimeSnapshots.decode(readPart(in));
            var slaps = SlapSnapshots.decode(readPart(in));
            var health = HealthSnapshots.decode(readPart(in));
            var animation = AnimationSnapshots.decode(readPart(in));
            var creatureView = CreatureViewSnapshots.decode(readPart(in));
            var movement = MovementSnapshots.decode(readPart(in));
            var positions = PositionSnapshots.decode(readPart(in));
            if (in.read() != -1) throw new IOException("Trailing simulation payload");
            var systems = new GameLoopManager.SystemState(creatures, spellbooks, chickens, creatureControllers, creatureAi,
                    parties, taskAllocation, tileTasks, roomTasks, entityTasks, taskGraph, taskManager, mana, experience,
                    economy, recruitment, chickenSpawning, lifetimes, slaps, health, animation, creatureView, movement, positions);
            return new SimulationArchive(definition, new SimulationState(entities, keepers, players, rooms, flashes, level, systems, triggers, random, results, loops));
    }

    public static SimulationArchive read(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES || size < HEADER_BYTES + DIGEST_BYTES) throw new IOException("Invalid simulation archive size");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int)size + 1);
            if (bytes.length != size || input.read() != -1) throw new IOException("Archive changed while reading");
            return decode(bytes);
        }
    }

    /** Encoding finishes before touching the destination; failed writes preserve the previous archive. */
    public static void write(Path path, SimulationArchive archive) throws IOException {
        byte[] bytes = encode(archive);
        writeAtomically(path, channel -> {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        });
    }

    @FunctionalInterface interface FileWriter {void write(FileChannel channel) throws IOException;}
    static void writeAtomically(Path path, FileWriter writer) throws IOException {
        var target = path.toAbsolutePath().normalize();Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".mackeeper-save-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {writer.write(channel);channel.force(true);}
            // The same-directory atomic rename either replaces the complete file or leaves the old one intact.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }

    @FunctionalInterface private interface Encoder<T> {byte[] encode(T value) throws IOException;}
    @FunctionalInterface private interface Decoder<T> {T decode(byte[] value) throws IOException;}
    private static <T> void writeList(DataOutputStream out, List<T> values, int maximum, Encoder<T> encoder) throws IOException {
        count(values.size(), maximum);out.writeInt(values.size());
        for (var value : values) writePart(out, encoder.encode(value));
    }
    private static <T> List<T> readList(DataInputStream in, int maximum, Decoder<T> decoder) throws IOException {
        int size = count(in.readInt(), maximum);var values = new ArrayList<T>();
        for (int i = 0; i < size; i++) values.add(decoder.decode(readPart(in)));
        return List.copyOf(values);
    }
    private static void writePart(DataOutputStream out, byte[] bytes) throws IOException {
        count(bytes.length, MAX_PART_BYTES);out.writeInt(bytes.length);out.write(bytes);
    }
    private static byte[] readPart(DataInputStream in) throws IOException {
        int size = count(in.readInt(), Math.min(MAX_PART_BYTES, in.available()));
        byte[] value = in.readNBytes(size);
        if (value.length != size) throw new EOFException("Truncated archive section");
        return value;
    }
    private static byte[] hash(byte[] bytes) {return sha256().digest(bytes);}
    private static MessageDigest sha256() {
        try {return MessageDigest.getInstance("SHA-256");}
        catch (NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    private static final class BoundedOutput extends FilterOutputStream {
        private final int maximum;private int size;
        BoundedOutput(OutputStream out, int maximum) {super(out);this.maximum = maximum;}
        private void reserve(int length) throws IOException {if (length > maximum - size) throw new IOException("Simulation archive exceeds size limit");size += length;}
        @Override public void write(int value) throws IOException {reserve(1);out.write(value);}
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {reserve(length);out.write(bytes, offset, length);}
    }
    private SimulationArchives() {}
}
