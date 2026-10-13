/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.view.HudPanelState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKHP v1, explicit HUD panel values without executable menu callbacks. */
public final class HudPanelSnapshots {
    public static final int MAX_BYTES = 256 * 1024;
    public static byte[] encode(HudPanelState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b4850);out.writeInt(1);FLOAT.write(out, state.updateRemainder());
            nullable(TEXT).write(out, state.cinematicText());nullable(INT).write(out, state.objectiveId());
            out.writeInt(state.pendingObjectives().size());for (int id : state.pendingObjectives()) out.writeInt(id);
            BOOLEAN.write(out, state.objectiveVisible());BOOLEAN.write(out, state.bottomCollapsed());out.writeInt(state.selectedTab());
            var menu = state.menu();BOOLEAN.write(out, menu.visible());enumeration(HudPanelState.Page.class).write(out, menu.page());
            nullable(enumeration(HudPanelState.Page.class)).write(out, menu.back());nullable(TEXT).write(out, menu.title());
            nullable(enumeration(HudPanelState.Destination.class)).write(out, menu.destination());
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static HudPanelState decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b4850 || in.readInt() != 1) throw new IOException("Unsupported HUD panel section");
            float time = FLOAT.read(in);String text = nullable(TEXT).read(in);Integer objective = nullable(INT).read(in);
            int size = count(in.readInt(), HudPanelState.MAX_PENDING);var pending = new ArrayList<Integer>();
            for (int i = 0; i < size; i++) pending.add(in.readInt());
            boolean visible = BOOLEAN.read(in), collapsed = BOOLEAN.read(in);int tab = in.readInt();
            var menu = new HudPanelState.Menu(BOOLEAN.read(in), enumeration(HudPanelState.Page.class).read(in),
                    nullable(enumeration(HudPanelState.Page.class)).read(in), nullable(TEXT).read(in),
                    nullable(enumeration(HudPanelState.Destination.class)).read(in));
            if (in.read() != -1) throw new IOException("Trailing HUD panel data");
            return new HudPanelState(time, text, objective, pending, visible, collapsed, tab, menu);
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid HUD panel section", failure);}
    }
    private HudPanelSnapshots() {}
}
