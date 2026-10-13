/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.network.game.GameHostedService;
import toniarts.openkeeper.game.state.session.*;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.*;

class GameResultDeliveryTest {
    @Test void localCallbackPreservesOutcomeAndFractionalSimulationTime() {
        var local=new LocalGameSession();List<GameResult> results=new ArrayList<>();
        local.addGameSessionListener(listener(results));
        local.endGame(false,(short)4,9.5);assertTrue(results.isEmpty());
        local.endGame(false,(short)3,123.75);
        assertEquals(1,results.size());assertFalse(results.getFirst().isWon());
        assertEquals(123.75,results.getFirst().getElapsedSeconds());
    }
    @Test void hostedClientCallbacksRetainDifferentPlayersResults() throws Exception {
        var host=new GameHostedService();List<GameResult> a=new ArrayList<>(),b=new ArrayList<>();
        connect(host,(short)3,listener(a));connect(host,(short)4,listener(b));
        host.endGame(false,(short)3,90.125);host.endGame(true,(short)4,110.5);host.endGame(true,(short)7,999);
        assertEquals(1,a.size());assertEquals(1,b.size());
        assertFalse(a.getFirst().isWon());assertTrue(b.getFirst().isWon());
        assertEquals(90.125,a.getFirst().getElapsedSeconds());assertEquals(110.5,b.getFirst().getElapsedSeconds());
    }
    @Test void terminalTimeRejectsInvalidValuesAndFirstFieldsCannotBeRewritten() {
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->GameResult.completed(true,bad));
        var result=GameResult.completed(false,23.5);
        result.setData(GameResult.ResultType.LEVEL_WON,true);result.setData(GameResult.ResultType.TIME_TAKEN,99d);
        assertFalse(result.isWon());assertEquals(23.5,result.getElapsedSeconds());
    }
    private static GameSessionListener listener(List<GameResult> results) {
        return proxy(GameSessionListener.class,(name,args)-> {
            if(name.equals("onGameEnded"))results.add(GameResult.completed((boolean)args[0],(double)args[1]));
            else if(!name.equals("equals"))fail(name);
            return false;
        });
    }
}
