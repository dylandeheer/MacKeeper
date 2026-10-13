/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.view.MessageState;
import toniarts.openkeeper.view.SystemMessageState.MessageType;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKMS v1: message lifetimes, read status, order and open-message identity. */
public final class MessageSnapshots {
    public static final int MAX_BYTES = 1024 * 1024;
    public static byte[] encode(MessageState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new FilterOutputStream(bytes) {
            private int written;
            private void reserve(int size) throws IOException {if (size < 0 || size > MAX_BYTES - written) throw new IOException("Message section too large");written += size;}
            @Override public void write(int value) throws IOException {reserve(1);out.write(value);}
            @Override public void write(byte[] values, int offset, int length) throws IOException {reserve(length);out.write(values, offset, length);}
        })) {
            out.writeInt(0x4d4b4d53);out.writeInt(1);BOOLEAN.write(out, state.enabled());out.writeInt(state.messages().size());
            for (var message : state.messages()) writeMessage(out, message);
            var box = state.box();enumeration(MessageState.BoxMode.class).write(out, box.mode());nullable(TEXT).write(out, box.text());
            out.writeInt(box.messageIndex());BOOLEAN.write(out, box.detachedMessage() != null);
            if (box.detachedMessage() != null) writeMessage(out, box.detachedMessage());
        }
        return bytes.toByteArray();
    }
    public static MessageState decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b4d53 || in.readInt() != 1) throw new IOException("Unsupported message section");
            boolean enabled = BOOLEAN.read(in);int size = count(in.readInt(), MessageState.MAX_MESSAGES);
            var messages = new ArrayList<MessageState.Message>();for (int i = 0; i < size; i++) messages.add(readMessage(in));
            var box = new MessageState.Box(enumeration(MessageState.BoxMode.class).read(in), nullable(TEXT).read(in), in.readInt(), BOOLEAN.read(in) ? readMessage(in) : null);
            if (in.read() != -1) throw new IOException("Trailing message section data");
            return new MessageState(enabled, messages, box);
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid message section", failure);}
    }
    private static void writeMessage(DataOutput out, MessageState.Message message) throws IOException {
        enumeration(MessageType.class).write(out, message.type());TEXT.write(out, message.text());BOOLEAN.write(out, message.unread());DOUBLE.write(out, message.ageSeconds());
    }
    private static MessageState.Message readMessage(DataInput in) throws IOException {
        return new MessageState.Message(enumeration(MessageType.class).read(in), TEXT.read(in), BOOLEAN.read(in), DOUBLE.read(in));
    }
    private MessageSnapshots() {}
}
