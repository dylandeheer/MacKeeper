/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import java.util.Objects;
import toniarts.openkeeper.game.sound.SoundSnapshot;
import toniarts.openkeeper.view.*;
import toniarts.openkeeper.view.camera.CameraState;
import toniarts.openkeeper.view.creature.CreatureCardsState;
import toniarts.openkeeper.view.fogofwar.FogSnapshot;

/** Joined logical client owners. Active HUD owners cease to exist on the terminal screen. */
public record ClientState(ClientModelState model, PlayerProgressState progress, PlayerScreenState screen,
        FogSnapshot fog, SoundSnapshot sound, Active active) {
    public record Active(CameraState camera, MessageState messages, HudPanelState hud,
            InteractionSelection interaction, CreatureCardsState cards, HandViewState hand, boolean consoleEnabled) {
        public Active {
            Objects.requireNonNull(camera);Objects.requireNonNull(messages);Objects.requireNonNull(hud);
            Objects.requireNonNull(interaction);Objects.requireNonNull(cards);Objects.requireNonNull(hand);
            short player = camera.playerId();
            if (interaction.playerId() != player || cards.playerId() != player || hand.playerId() != player)
                throw new IllegalArgumentException("Active client owners belong to different players");
        }
    }
    public ClientState {
        Objects.requireNonNull(model);Objects.requireNonNull(progress);Objects.requireNonNull(screen);
        Objects.requireNonNull(fog);Objects.requireNonNull(sound);
        short player = model.playerId();
        if (progress.playerId() != player || screen.playerId() != player
                || fog.knowledge().viewerId() != player || sound.playerId() != player
                || active != null && active.camera().playerId() != player)
            throw new IllegalArgumentException("Client owners belong to different players");
        if (screen.playerEnabled() != (active != null) || screen.awaitingEndInput() && progress.result() == null)
            throw new IllegalArgumentException("Client owners disagree on the player lifecycle");
        if (active != null && (active.camera().transitionEnd() != screen.transitionEnd()
                || !Objects.equals(active.hud().cinematicText(), screen.cinematicText())))
            throw new IllegalArgumentException("Client owners disagree on cinematic state");
        if (active != null && active.hud().menu().destination() == HudPanelState.Destination.DEBRIEFING && progress.result() == null)
            throw new IllegalArgumentException("Debriefing requires an observed result");
    }
}
