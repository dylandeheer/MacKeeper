/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import toniarts.openkeeper.game.random.SimulationRandom;

/** MKRG v1 is a single SplitMix64 state word, not a serialized JDK generator. */
public final class RandomSnapshots {
    public static byte[] encode(SimulationRandom.State state) throws IOException {
        var bytes=new ByteArrayOutputStream(16);
        try(var out=new DataOutputStream(bytes)){out.writeInt(0x4d4b5247);out.writeInt(1);out.writeLong(state.value());}
        return bytes.toByteArray();
    }
    public static SimulationRandom.State decode(byte[] bytes) throws IOException {
        if(bytes.length!=16)throw new IOException("Invalid random section length");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=0x4d4b5247 || in.readInt()!=1)throw new IOException("Unsupported random section");
            return new SimulationRandom.State(in.readLong());
        }
    }
    private RandomSnapshots(){ }
}
