/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import toniarts.openkeeper.game.state.session.ClientState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKLS v2: bounded joined local save, with a checksum over the header and all owner sections. */
public final class LocalSessionArchives {
    private static final int MAGIC = 0x4d4b4c53, VERSION = 2;
    private static final int HEADER = 12, DIGEST = 32;
    public static final int MAX_BYTES = 384 * 1024 * 1024;
    private static final int MAX_PAYLOAD = MAX_BYTES - HEADER - DIGEST;

    public static byte[] encode(LocalSessionArchive archive) throws IOException {
        var payload = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new FilterOutputStream(payload) {
            private int written;
            private void reserve(int length) throws IOException {
                if (length < 0 || length > MAX_PAYLOAD - written) throw new IOException("Local save exceeds size limit");
                written += length;
            }
            @Override public void write(int value) throws IOException {reserve(1);out.write(value);}
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {reserve(length);out.write(bytes, offset, length);}
        })) {
            BOOLEAN.write(out, archive.campaign());
            section(out, SimulationArchives.encode(archive.server()));
            var client = archive.client();
            section(out, ClientModelSnapshots.encode(client.model()));
            section(out, PlayerProgressSnapshots.encode(client.progress()));
            section(out, PlayerScreenSnapshots.encode(client.screen()));
            section(out, FogSnapshots.encode(client.fog()));
            section(out, SoundSnapshots.encode(client.sound()));
            section(out, WorldViewSnapshots.encode(client.worldView()));
            BOOLEAN.write(out, client.active() != null);
            if (client.active() != null) {
                var active = client.active();
                section(out, CameraSnapshots.encode(active.camera()));
                section(out, MessageSnapshots.encode(active.messages()));
                section(out, HudPanelSnapshots.encode(active.hud()));
                section(out, InteractionSnapshots.encode(active.interaction()));
                section(out, CreatureCardSnapshots.encode(active.cards()));
                section(out, HandViewSnapshots.encode(active.hand()));
                BOOLEAN.write(out, active.consoleEnabled());
            }
        }
        var bytes = new ByteArrayOutputStream(HEADER + payload.size() + DIGEST);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(payload.size());payload.writeTo(out);
            out.write(digest().digest(bytes.toByteArray()));
        }
        return bytes.toByteArray();
    }

    public static LocalSessionArchive decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        if (bytes.length < HEADER + DIGEST) throw new IOException("Truncated local save");
        try (var envelope = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (envelope.readInt() != MAGIC || envelope.readInt() != VERSION) throw new IOException("Unsupported local save");
            int size = count(envelope.readInt(), MAX_PAYLOAD);
            if (bytes.length != HEADER + size + DIGEST) throw new IOException("Local save length differs");
            var expected = digest();expected.update(bytes, 0, HEADER + size);
            if (!MessageDigest.isEqual(expected.digest(), java.util.Arrays.copyOfRange(bytes, HEADER + size, bytes.length)))
                throw new IOException("Local save checksum differs");
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes, HEADER, size))) {
                boolean campaign = BOOLEAN.read(in);
                var server = SimulationArchives.decode(section(in, SimulationArchives.MAX_PAYLOAD_BYTES + SimulationArchives.HEADER_BYTES + SimulationArchives.DIGEST_BYTES));
                var model = ClientModelSnapshots.decode(section(in, ClientModelSnapshots.MAX_BYTES));
                var progress = PlayerProgressSnapshots.decode(section(in, PlayerProgressSnapshots.MAX_BYTES));
                var screen = PlayerScreenSnapshots.decode(section(in, PlayerScreenSnapshots.MAX_BYTES));
                var fog = FogSnapshots.decode(section(in, FogSnapshots.MAX_BYTES));
                var sound = SoundSnapshots.decode(section(in, SoundSnapshots.MAX_BYTES));
                var worldView = WorldViewSnapshots.decode(section(in, WorldViewSnapshots.MAX_BYTES));
                ClientState.Active active = null;
                if (BOOLEAN.read(in)) active = new ClientState.Active(
                        CameraSnapshots.decode(section(in, CameraSnapshots.MAX_BYTES)),
                        MessageSnapshots.decode(section(in, MessageSnapshots.MAX_BYTES)),
                        HudPanelSnapshots.decode(section(in, HudPanelSnapshots.MAX_BYTES)),
                        InteractionSnapshots.decode(section(in, InteractionSnapshots.MAX_BYTES)),
                        CreatureCardSnapshots.decode(section(in, CreatureCardSnapshots.MAX_BYTES)),
                        HandViewSnapshots.decode(section(in, HandViewSnapshots.MAX_BYTES)), BOOLEAN.read(in));
                if (in.read() != -1) throw new IOException("Trailing local save payload");
                return new LocalSessionArchive(campaign, server, new ClientState(model, progress, screen, fog, sound, worldView, active));
            }
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid local save", failure);}
    }

    public static LocalSessionArchive read(Path path) throws IOException {
        long size = Files.size(path);
        if (size < HEADER + DIGEST || size > MAX_BYTES) throw new IOException("Invalid local save size");
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes((int) size + 1);
            if (bytes.length != size || input.read() != -1) throw new IOException("Local save changed while reading");
            return decode(bytes);
        }
    }
    /** Finish encoding before touching the destination; atomic replacement preserves the previous save on failure. */
    public static void write(Path path, LocalSessionArchive archive) throws IOException {
        byte[] bytes = encode(archive);
        SimulationArchives.writeAtomically(path, channel -> {
            var buffer = ByteBuffer.wrap(bytes);while (buffer.hasRemaining()) channel.write(buffer);
        });
    }
    private static void section(DataOutputStream out, byte[] bytes) throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] section(DataInputStream in, int maximum) throws IOException {
        int size = count(in.readInt(), maximum);
        if (size > in.available()) throw new EOFException("Truncated local save section");
        return in.readNBytes(size);
    }
    private static MessageDigest digest() {
        try {return MessageDigest.getInstance("SHA-256");}
        catch (NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    private LocalSessionArchives() {}
}
