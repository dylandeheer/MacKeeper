/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import java.util.List;
import java.util.Objects;

/** Message contents and remaining display history, independent of Nifty elements. */
public record MessageState(boolean enabled, List<Message> messages, Box box) {
    public static final int MAX_MESSAGES = 4096;
    public static final double LIFETIME_SECONDS = 60;
    public MessageState {
        messages = List.copyOf(messages);Objects.requireNonNull(box);
        if (messages.size() > MAX_MESSAGES || box.messageIndex() >= messages.size()) throw new IllegalArgumentException("Invalid message list/reference");
    }
    public record Message(SystemMessageState.MessageType type, String text, boolean unread, double ageSeconds) {
        public Message {
            Objects.requireNonNull(type);text = validateText(text);
            if (!Double.isFinite(ageSeconds) || ageSeconds < 0 || ageSeconds > LIFETIME_SECONDS) throw new IllegalArgumentException("Invalid message age");
        }
    }
    public enum BoxMode {CLOSED, TEXT, SYSTEM, FOCUS}
    /** An expired message can remain open after its icon leaves the bar. */
    public record Box(BoxMode mode, String text, int messageIndex, Message detachedMessage) {
        public Box {
            Objects.requireNonNull(mode);
            if (messageIndex < -1) throw new IllegalArgumentException("Invalid selected message index");
            if (mode == BoxMode.CLOSED) {
                if (text != null || messageIndex != -1 || detachedMessage != null) throw new IllegalArgumentException("Closed message box has contents");
            } else {
                text = validateText(text);
                if (mode == BoxMode.TEXT) {
                    if (messageIndex != -1 || detachedMessage != null) throw new IllegalArgumentException("Plain text has a message reference");
                } else if ((messageIndex >= 0) == (detachedMessage != null)) throw new IllegalArgumentException("Selected message must have exactly one source");
            }
        }
        public static Box closed() {return new Box(BoxMode.CLOSED, null, -1, null);}
    }
    private static String validateText(String text) {
        Objects.requireNonNull(text);
        if (text.length() > 65_536) throw new IllegalArgumentException("Message text too long");
        return text;
    }
}
