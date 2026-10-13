/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import java.util.List;
import java.util.Objects;

/** Persistent HUD panels; screen routing, interaction and creature-card owners are separate. */
public record HudPanelState(float updateRemainder, String cinematicText, Integer objectiveId,
        List<Integer> pendingObjectives, boolean objectiveVisible, boolean bottomCollapsed,
        int selectedTab, Menu menu) {
    public static final int MAX_PENDING = 4096;
    public HudPanelState {
        if (!Float.isFinite(updateRemainder) || updateRemainder < 0 || updateRemainder >= .25f)
            throw new IllegalArgumentException("Invalid HUD update remainder");
        validateText(cinematicText);
        if (objectiveId != null) validateObjective(objectiveId);
        pendingObjectives = List.copyOf(pendingObjectives);
        if (pendingObjectives.size() > MAX_PENDING) throw new IllegalArgumentException("Too many pending objectives");
        pendingObjectives.forEach(HudPanelState::validateObjective);
        if (selectedTab < 0 || selectedTab > 255) throw new IllegalArgumentException("Invalid HUD tab");
        Objects.requireNonNull(menu);
    }
    public enum Page {MAIN, QUIT, CONFIRMATION}
    public enum Destination {
        MAIN_MENU("quitToMainMenu()"), DEBRIEFING("quitToDebriefing()"), DESKTOP("quitToOS()");
        private final String action;
        Destination(String action) {this.action = action;}
        public String action() {return action;}
        public static Destination fromAction(String action) {
            for (var destination : values()) if (destination.action.equals(action)) return destination;
            throw new IllegalArgumentException("Unsupported confirmation destination");
        }
    }
    public record Menu(boolean visible, Page page, Page back, String title, Destination destination) {
        public Menu {
            Objects.requireNonNull(page);
            if (page == Page.CONFIRMATION) {
                if (back == null || back == Page.CONFIRMATION || title == null || destination == null)
                    throw new IllegalArgumentException("Incomplete confirmation menu");
                validateText(title);
            } else if (back != null || title != null || destination != null) throw new IllegalArgumentException("Unexpected menu confirmation values");
        }
        public static Menu main(boolean visible) {return new Menu(visible, Page.MAIN, null, null, null);}
        public Menu visible(boolean value) {return new Menu(value, page, back, title, destination);}
    }
    private static void validateText(String text) {
        if (text != null && text.length() > 65536) throw new IllegalArgumentException("HUD text too long");
    }
    private static void validateObjective(int id) {
        if (id < 0 || id > 32767) throw new IllegalArgumentException("Invalid objective ID");
    }
}
