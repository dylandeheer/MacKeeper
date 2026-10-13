/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import com.jme3.system.AppSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.game.data.CampaignLevel.LevelType.*;

class CampaignProgressTest {
    @TempDir Path temp;
    private Settings settings() { return new Settings(new AppSettings(true), temp.resolve("settings.properties")); }
    private CampaignLevel level(int n) { return new CampaignLevel(Level,n); }

    @Test void freshProfileHasTypedDefaultsAndFirstPlayableLevel() {
        Settings s=settings();
        assertEquals(Settings.LevelStatus.NOT_COMPLETED,s.getLevelStatus(level(1)));
        assertEquals(Settings.LevelStatus.NOT_COMPLETED,s.getLevelStatus(new CampaignLevel(MPD,1)));
        assertEquals(Settings.SecretLevelStatus.NOT_DISCOVED,s.getSecredLevelStatus(new CampaignLevel(Secret,1)));
        assertEquals(1,s.getNextPlayableLevel());
    }

    @Test void lossesCountAttemptsWithoutUnlockingOrErasingAnEarlierWin() throws Exception {
        Settings s=settings();
        s.recordCampaignResult(level(1),false);
        assertEquals(1,s.getLevelAttempts(level(1)));
        assertEquals(1,s.getNextPlayableLevel());
        s.recordCampaignResult(level(1),true);
        s.recordCampaignResult(level(1),false);
        s.save();
        Settings restored=settings();
        assertEquals(3,restored.getLevelAttempts(level(1)));
        assertEquals(Settings.LevelStatus.COMPLETED,restored.getLevelStatus(level(1)));
        assertEquals(2,restored.getNextPlayableLevel());
    }

    @Test void branchAttemptsAndModesStaySeparateAcrossRestart() throws Exception {
        Settings s=settings();
        var a=new CampaignLevel(Level,6,"a"); var b=new CampaignLevel(Level,6,"b");
        var mpd=new CampaignLevel(MPD,6,"a"); var secret=new CampaignLevel(Secret,6);
        s.recordCampaignResult(a,false);s.recordCampaignResult(a,true);s.recordCampaignResult(b,false);
        s.recordCampaignResult(mpd,true);s.recordCampaignResult(secret,true);s.save();
        Settings r=settings();
        assertEquals(2,r.getLevelAttempts(a));assertEquals(1,r.getLevelAttempts(b));
        assertEquals(1,r.getLevelAttempts(mpd));assertEquals(1,r.getLevelAttempts(secret));
        assertEquals(Settings.LevelStatus.COMPLETED,r.getLevelStatus(a));
        assertEquals(Settings.LevelStatus.NOT_COMPLETED,r.getLevelStatus(b));
        assertEquals(Settings.LevelStatus.COMPLETED,r.getLevelStatus(mpd));
        assertEquals(Settings.SecretLevelStatus.COMPLETED,r.getSecredLevelStatus(secret));
    }

    @Test void replayAndBonusWinDoNotRegressOrAdvanceMainCampaignImproperly() {
        Settings s=settings();
        for(int n=1;n<=5;n++)s.recordCampaignResult(level(n),true);
        s.recordCampaignResult(new CampaignLevel(Level,6,"b"),true);
        assertEquals(7,s.getNextPlayableLevel());
        s.recordCampaignResult(level(1),true);
        s.recordCampaignResult(new CampaignLevel(MPD,10),true);
        s.recordCampaignResult(new CampaignLevel(Secret,3),true);
        assertEquals(7,s.getInteger(Settings.Setting.LEVEL_NUMBER));
    }

    @Test void legacyBranchCountersMigrateWithoutAssigningThemToBonusModes() throws Exception {
        Settings s=settings();
        s.getAppSettings().put(Settings.Setting.LEVEL_ATTEMPTS.toString()+"6a",4);
        var branch=new CampaignLevel(Level,6,"a");
        assertEquals(4,s.getLevelAttempts(branch));
        assertEquals(0,s.getLevelAttempts(new CampaignLevel(MPD,6,"a")));
        s.recordCampaignResult(branch,false);s.save();
        assertEquals(5,settings().getLevelAttempts(branch));
    }

    @Test void resetClearsRealCampaignAndSecretKeysButPreservesMpd() throws Exception {
        Settings s=settings(); var branch=new CampaignLevel(Level,11,"c");
        var secret=new CampaignLevel(Secret,2);var mpd=new CampaignLevel(MPD,2);
        s.getAppSettings().put(Settings.Setting.LEVEL_ATTEMPTS.toString()+"11c",7);
        s.recordCampaignResult(branch,true);s.recordCampaignResult(secret,true);s.recordCampaignResult(mpd,true);
        s.resetCampaignProgress();s.save();Settings r=settings();
        assertEquals(0,r.getLevelAttempts(branch));assertEquals(0,r.getLevelAttempts(secret));
        assertEquals(Settings.LevelStatus.NOT_COMPLETED,r.getLevelStatus(branch));
        assertEquals(Settings.SecretLevelStatus.NOT_DISCOVED,r.getSecredLevelStatus(secret));
        assertEquals(Settings.LevelStatus.COMPLETED,r.getLevelStatus(mpd));assertEquals(1,r.getLevelAttempts(mpd));
    }

    @Test void completingAllBranchesUnlocksCampaignEndAndCounterDoesNotOverflow() {
        Settings s=settings();
        for(int n=1;n<=20;n++)s.recordCampaignResult(new CampaignLevel(Level,n,(n==6||n==11||n==15)?"a":null),true);
        assertEquals(21,s.getNextPlayableLevel());assertEquals(21,s.getInteger(Settings.Setting.LEVEL_NUMBER));
        s.getAppSettings().put(Settings.Setting.LEVEL_ATTEMPTS.toString()+"2",Integer.MAX_VALUE);
        s.getAppSettings().remove("CampaignAttempts.Level.2");s.recordCampaignResult(level(2),false);
        assertEquals(Integer.MAX_VALUE,s.getLevelAttempts(level(2)));
    }
    @Test void savedAttemptCountsOnceAcrossProfileReloadsAndCanUpgradeALoss() throws Exception {
        var attempt=java.util.UUID.randomUUID();var branch=new CampaignLevel(Level,6,"b");
        var s=settings();
        assertTrue(s.recordCampaignResult(branch,attempt,false));s.save();
        s=settings();assertFalse(s.recordCampaignResult(branch,attempt,false));
        assertEquals(1,s.getLevelAttempts(branch));
        assertTrue(s.recordCampaignResult(branch,attempt,true));s.save();
        s=settings();assertFalse(s.recordCampaignResult(branch,attempt,true));
        assertFalse(s.recordCampaignResult(branch,attempt,false));
        assertEquals(1,s.getLevelAttempts(branch));assertEquals(Settings.LevelStatus.COMPLETED,s.getLevelStatus(branch));
        assertTrue(s.recordCampaignResult(branch,java.util.UUID.randomUUID(),false));
        assertEquals(2,s.getLevelAttempts(branch));
    }

    @Test void receiptCannotBeReusedForAnotherBranchOrModeAndResetRetainsMpdHistory() throws Exception {
        var attempt=java.util.UUID.randomUUID();var mpdAttempt=java.util.UUID.randomUUID();
        var branch=new CampaignLevel(Level,11,"c");var mpd=new CampaignLevel(MPD,2);
        var s=settings();s.recordCampaignResult(branch,attempt,true);s.recordCampaignResult(mpd,mpdAttempt,true);
        assertThrows(IllegalArgumentException.class,()->s.recordCampaignResult(new CampaignLevel(Level,11,"a"),attempt,true));
        assertThrows(IllegalArgumentException.class,()->s.recordCampaignResult(mpd,attempt,true));
        assertEquals(0,s.getLevelAttempts(new CampaignLevel(Level,11,"a")));
        s.resetCampaignProgress();s.save();var restored=settings();
        assertEquals(0,restored.getLevelAttempts(branch));
        assertTrue(restored.recordCampaignResult(branch,attempt,true));
        assertEquals(1,restored.getLevelAttempts(branch));
        assertFalse(restored.recordCampaignResult(mpd,mpdAttempt,true));
        assertEquals(1,restored.getLevelAttempts(mpd));
    }

    @Test void failedAtomicProfileWriteRetainsReceiptAndCanBeRetriedTogether() throws Exception {
        Path target=temp.resolve("blocked");java.nio.file.Files.createDirectories(target);
        java.nio.file.Files.writeString(target.resolve("child"),"keep");
        var s=new Settings(new AppSettings(true),target);var attempt=java.util.UUID.randomUUID();
        s.recordCampaignResult(level(1),attempt,true);
        assertThrows(java.io.IOException.class,s::save);
        assertFalse(s.recordCampaignResult(level(1),attempt,true));assertEquals(1,s.getLevelAttempts(level(1)));
        java.nio.file.Files.delete(target.resolve("child"));java.nio.file.Files.delete(target);s.save();
        var r=new Settings(new AppSettings(true),target);
        assertFalse(r.recordCampaignResult(level(1),attempt,true));assertEquals(1,r.getLevelAttempts(level(1)));
        assertEquals(Settings.LevelStatus.COMPLETED,r.getLevelStatus(level(1)));
    }

}
