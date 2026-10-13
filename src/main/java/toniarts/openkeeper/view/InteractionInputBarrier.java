/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import java.util.HashSet;
import java.util.Set;

/** A release/repeat belonging to a pre-load press cannot act on the newly loaded world. */
final class InteractionInputBarrier {
    private boolean restored;
    private final Set<Integer> buttons = new HashSet<>();
    private final Set<Integer> keys = new HashSet<>();
    void reset() {restored = true;buttons.clear();keys.clear();}
    boolean mouse(int button, boolean pressed) {
        if (!restored) return true;
        if (pressed) {buttons.add(button);return true;}
        return buttons.remove(button);
    }
    boolean key(int key, boolean pressed, boolean repeating) {
        if (!restored) return true;
        if (!pressed) return keys.remove(key);
        if (repeating) return keys.contains(key);
        keys.add(key);return true;
    }
}
