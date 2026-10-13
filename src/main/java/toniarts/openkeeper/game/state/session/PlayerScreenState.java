/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import java.util.Objects;

/** Logical destination and player lifecycle. Decorative screen transitions settle to this destination. */
public record PlayerScreenState(short playerId, boolean playerEnabled, boolean paused, boolean transitionEnd,
        Route route, String cinematicText, boolean awaitingEndInput) {
    public enum Route {
        HUD("hud"), CINEMATIC("cinematic"), EMPTY("empty"), POSSESSION("possession");
        private final String screenId;
        Route(String screenId) { this.screenId = screenId; }
        public String screenId() { return screenId; }
        public static Route fromScreenId(String id) {
            for (var route : values()) if (route.screenId.equals(id)) return route;
            throw new IllegalArgumentException("Unknown player screen " + id);
        }
    }
    public PlayerScreenState {
        if (playerId < 0 || playerId > 255) throw new IllegalArgumentException("Invalid screen player");
        Objects.requireNonNull(route);
        if (cinematicText != null && cinematicText.length() > 65536)
            throw new IllegalArgumentException("Cinematic text is too long");
        if (playerEnabled == awaitingEndInput || awaitingEndInput && route != Route.CINEMATIC)
            throw new IllegalArgumentException("Inconsistent player screen lifecycle");
    }
}
