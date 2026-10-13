/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.KeeperState;
import toniarts.openkeeper.game.state.session.ClientModelState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKCM v1 joins client-observed keeper values with their undelivered notifications. */
public final class ClientModelSnapshots {
    public static final int MAX_BYTES=65*1024*1024;
    public static byte[] encode(ClientModelState state) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b434d);out.writeInt(1);out.writeShort(state.playerId());out.writeInt(state.keepers().size());
            for(var keeper:state.keepers()) writeSection(out,KeeperSnapshots.encode(keeper));
            writeSection(out,PresentationSnapshots.encode(state.pending()));
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static ClientModelState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0x4d4b434d || in.readInt()!=1) throw new IOException("Unsupported client model section");
            short player=in.readShort();int n=count(in.readInt(),256);var keepers=new ArrayList<KeeperState>();
            for(int i=0;i<n;i++) keepers.add(KeeperSnapshots.decode(readSection(in,KeeperSnapshots.MAX_BYTES)));
            var pending=PresentationSnapshots.decode(readSection(in,PresentationSnapshots.MAX_BYTES));
            var state=new ClientModelState(player,keepers,pending);
            if(in.read()!=-1) throw new IOException("Trailing client model data");return state;
        } catch(IllegalArgumentException|NullPointerException e) {throw new IOException("Invalid client model section",e);}
    }
    private static void writeSection(DataOutput out,byte[] bytes)throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] readSection(DataInputStream in,int maximum)throws IOException {
        int n=count(in.readInt(),maximum);if(n>in.available())throw new EOFException("Truncated client model section");
        byte[] bytes=new byte[n];in.readFully(bytes);return bytes;
    }
    private ClientModelSnapshots() {}
}
