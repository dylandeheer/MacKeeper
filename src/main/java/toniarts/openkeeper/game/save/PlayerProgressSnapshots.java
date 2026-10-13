/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.Map;
import java.util.UUID;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.game.state.session.PlayerProgressState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKPP v1: local attempt identity, campaign branch and already observed terminal result. */
public final class PlayerProgressSnapshots {
    private static final int MAGIC = 0x4d4b5050;
    private static final int MAX_BYTES = 257 * 1024;

    public static byte[] encode(PlayerProgressState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(1);out.writeShort(state.playerId());
            out.writeLong(state.attemptId().getMostSignificantBits());
            out.writeLong(state.attemptId().getLeastSignificantBits());
            out.writeBoolean(state.campaign() != null);
            if (state.campaign() != null) {
                out.writeUTF(state.campaign().type().name());out.writeInt(state.campaign().level());
                out.writeUTF(state.campaign().variation());
            }
            byte[] result = ResultSnapshots.encode(new ResultSnapshots.State(state.result() == null
                    ? Map.of() : Map.of(state.playerId(), state.result())));
            out.writeInt(result.length);out.write(result);
        }
        count(bytes.size(), MAX_BYTES);
        return bytes.toByteArray();
    }

    public static PlayerProgressState decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Unsupported player progress section");
            short player = in.readShort();
            UUID attempt = new UUID(in.readLong(), in.readLong());
            PlayerProgressState.Campaign campaign = null;
            if (BOOLEAN.read(in)) campaign = new PlayerProgressState.Campaign(
                    CampaignLevel.LevelType.valueOf(in.readUTF()), in.readInt(), in.readUTF());
            int length = count(in.readInt(), 256 * 1024);
            if (length > in.available()) throw new EOFException("Truncated player result");
            var results = ResultSnapshots.decode(in.readNBytes(length)).results();
            if (results.size() > 1 || !results.isEmpty() && !results.containsKey(player))
                throw new IOException("Terminal result belongs to another player");
            if (in.read() != -1) throw new IOException("Trailing player progress data");
            return new PlayerProgressState(player, attempt, campaign, results.get(player));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid player progress section", e);
        }
    }
    private PlayerProgressSnapshots() { }
}
