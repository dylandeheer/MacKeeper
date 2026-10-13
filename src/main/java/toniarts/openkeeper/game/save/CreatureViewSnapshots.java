/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Creature animation-transition clock; query membership is rebuilt without side effects. */
public final class CreatureViewSnapshots {
    private static final int MAGIC = 0x4d4b5657, VERSION = 1, BYTES = 16;
    public static byte[] encode(LifetimeSnapshots.Clock clock) throws IOException {
        var bytes = new ByteArrayOutputStream(BYTES);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);DOUBLE.write(out,clock.elapsed());
        }
        return bytes.toByteArray();
    }
    public static LifetimeSnapshots.Clock decode(byte[] bytes) throws IOException {
        if (bytes.length != BYTES) throw new IOException("Invalid creature view section length");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported creature view section");
            return new LifetimeSnapshots.Clock(DOUBLE.read(in));
        } catch (IllegalArgumentException e) {throw new IOException("Invalid creature view time",e);}
    }
    private CreatureViewSnapshots() { }
}
