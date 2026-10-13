/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import java.util.Objects;
import java.util.UUID;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.game.data.GameResult;

/** Client-observed outcome and stable playthrough identity; never applies profile credit on restore. */
public record PlayerProgressState(short playerId, UUID attemptId, Campaign campaign, GameResult.State result) {
    public record Campaign(CampaignLevel.LevelType type, int level, String variation) {
        public Campaign {
            Objects.requireNonNull(type);
            Objects.requireNonNull(variation);
            if (level < 0 || level > 65535 || !variation.matches("[a-zA-Z]{0,16}"))
                throw new IllegalArgumentException("Invalid campaign identity");
        }
        public static Campaign of(CampaignLevel level) {
            return level == null ? null : new Campaign(level.getType(), level.getLevel(), level.getVariation());
        }
        public CampaignLevel bind() { return new CampaignLevel(type, level, variation); }
    }

    public PlayerProgressState {
        if (playerId < 0 || playerId > 255) throw new IllegalArgumentException("Invalid progress player");
        Objects.requireNonNull(attemptId);
        if (result != null && (!(result.values().get(GameResult.ResultType.LEVEL_WON) instanceof Boolean)
                || !(result.values().get(GameResult.ResultType.TIME_TAKEN) instanceof Number)))
            throw new IllegalArgumentException("Missing terminal outcome or elapsed time");
    }

    public static PlayerProgressState fresh(short playerId, CampaignLevel campaign) {
        return new PlayerProgressState(playerId, UUID.randomUUID(), Campaign.of(campaign), null);
    }

    public void validateBinding(short player, CampaignLevel level) {
        if (playerId != player || !Objects.equals(campaign, Campaign.of(level)))
            throw new IllegalArgumentException("Player progress belongs to another player or campaign");
    }
}
