/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.state.session.PlayerProgressState;
import static org.junit.jupiter.api.Assertions.*;

class PlayerProgressSnapshotsTest {
    @Test void retainsAllCampaignModesBranchesAndObservedOutcomeAcrossRepeatedLoads() throws Exception {
        for(var type:CampaignLevel.LevelType.values())for(String branch:List.of("","a","b","c")) {
            var campaign=new CampaignLevel(type,11,branch);
            var result=GameResult.completed(true,92.125);
            result.setData(GameResult.ResultType.GOLD_MINED,Long.MAX_VALUE);
            result.setData(GameResult.ResultType.TOTAL_RATING,null);
            var before=new PlayerProgressState((short)3,UUID.randomUUID(),PlayerProgressState.Campaign.of(campaign),result.captureState());
            var saved=before;
            for(int i=0;i<3;i++)saved=PlayerProgressSnapshots.decode(PlayerProgressSnapshots.encode(saved));
            assertEquals(before,saved);assertEquals(campaign.getType(),saved.campaign().bind().getType());
            assertEquals(branch,saved.campaign().bind().getVariation());saved.validateBinding((short)3,campaign);
            assertThrows(IllegalArgumentException.class,()->before.validateBinding((short)4,campaign));
            assertThrows(IllegalArgumentException.class,()->before.validateBinding((short)3,null));
        }
    }

    @Test void freshPlaythroughIdentityDiffersWhileNonCampaignAndUndeliveredResultsRemainAbsent() throws Exception {
        var fresh=PlayerProgressState.fresh((short)3,null);
        assertNotEquals(fresh.attemptId(),PlayerProgressState.fresh((short)3,null).attemptId());
        var copy=PlayerProgressSnapshots.decode(PlayerProgressSnapshots.encode(fresh));
        assertEquals(fresh,copy);assertNull(copy.campaign());assertNull(copy.result());
        copy.validateBinding((short)3,null);
        assertThrows(IllegalArgumentException.class,()->new PlayerProgressState((short)-1,UUID.randomUUID(),null,null));
        assertThrows(IllegalArgumentException.class,()->new PlayerProgressState((short)3,UUID.randomUUID(),null,new GameResult().captureState()));
        assertThrows(IllegalArgumentException.class,()->new PlayerProgressState.Campaign(CampaignLevel.LevelType.Level,-1,""));
        assertThrows(IllegalArgumentException.class,()->new PlayerProgressState.Campaign(CampaignLevel.LevelType.Level,1,"../x"));
    }

    @Test void rejectsTruncatedTrailingMalformedAndMismatchedResultData() throws Exception {
        byte[] data=PlayerProgressSnapshots.encode(PlayerProgressState.fresh((short)3,null));
        for(int i=0;i<data.length;i++) {
            byte[] cut=Arrays.copyOf(data,i);assertThrows(IOException.class,()->PlayerProgressSnapshots.decode(cut));
        }
        assertThrows(IOException.class,()->PlayerProgressSnapshots.decode(Arrays.copyOf(data,data.length+1)));
        for(int offset:new int[]{0,4,27}) {
            byte[] corrupt=data.clone();ByteBuffer.wrap(corrupt).putInt(offset,Integer.MAX_VALUE);
            assertThrows(IOException.class,()->PlayerProgressSnapshots.decode(corrupt));
        }
        byte[] flag=data.clone();flag[26]=2;assertThrows(IOException.class,()->PlayerProgressSnapshots.decode(flag));
        var foreign=ResultSnapshots.encode(new ResultSnapshots.State(Map.of((short)4,GameResult.completed(false,1).captureState())));
        var bytes=new ByteArrayOutputStream();bytes.write(data,0,27);
        try(var out=new DataOutputStream(bytes)){out.writeInt(foreign.length);out.write(foreign);}
        assertThrows(IOException.class,()->PlayerProgressSnapshots.decode(bytes.toByteArray()));
    }
}
