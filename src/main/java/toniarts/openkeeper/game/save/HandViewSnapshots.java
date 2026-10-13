/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.component.ViewType;
import toniarts.openkeeper.view.HandViewState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKHV v1: ordered observed hand items; live controls, pointer and decorative animation are rebuilt. */
public final class HandViewSnapshots {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static byte[] encode(HandViewState state) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b4856);out.writeInt(1);out.writeShort(state.playerId());out.writeInt(state.capacity());
            BOOLEAN.write(out,state.enabled());BOOLEAN.write(out,state.visible());out.writeInt(state.items().size());
            for(var item:state.items()) {
                ENTITY_ID.write(out,item.entityId());out.writeInt(item.index());enumeration(ViewType.class).write(out,item.type());out.writeShort(item.definitionId());
            }
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static HandViewState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0x4d4b4856 || in.readInt()!=1) throw new IOException("Unsupported hand view section");
            short player=in.readShort();int capacity=in.readInt();boolean enabled=BOOLEAN.read(in),visible=BOOLEAN.read(in);
            int n=count(in.readInt(),HandViewState.MAX_ITEMS);var items=new ArrayList<HandViewState.Item>();
            for(int i=0;i<n;i++) items.add(new HandViewState.Item(ENTITY_ID.read(in),in.readInt(),enumeration(ViewType.class).read(in),in.readShort()));
            var state=new HandViewState(player,capacity,enabled,visible,items);
            if(in.read()!=-1) throw new IOException("Trailing hand view data");return state;
        } catch(IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid hand view section",failure);}
    }
    private HandViewSnapshots() {}
}
