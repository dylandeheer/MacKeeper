/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.state.session.PlayerService;
import static toniarts.openkeeper.game.data.GameResult.ResultType.*;
import static org.junit.jupiter.api.Assertions.*;

class ResultContinuationTest {
    @Test void multiplePlayersContinueWithoutReplayingOldResultsOrLosingStatistics() throws Exception {
        double[] clock = {12.125};var before = new ArrayList<List<Object>>();var after = new ArrayList<List<Object>>();
        var live = new GameResultController(() -> clock[0], service(before), List.of((short)3,(short)4,(short)5));
        live.endGame((short)3, false);
        var outcome = live.getResult((short)3);
        outcome.setData(GOLD_MINED, 9007199254740993L);outcome.setData(SPECIALS_FOUND, (byte)2);
        outcome.setData(SPECIALS_MAX, (short)5);outcome.setData(LAND_OWNED, 129);
        outcome.setData(TOTAL_RATING, 3.25f);outcome.setData(OVERALL_TOTAL_RATING, 9.125d);
        outcome.setData(CREATURE_TRAINING, null);
        clock[0] = 99.875;live.endGame((short)4, true);
        var saved = live.captureState();
        var restored = GameResultController.restoreState(roundTrip(saved), () -> clock[0], service(after), List.of((short)3,(short)4,(short)5));
        assertTrue(after.isEmpty());assertEquals(saved, restored.captureState());
        outcome.setData(ATTEMPTS_AT_LEVEL, 7);assertFalse(saved.results().get((short)3).values().containsKey(ATTEMPTS_AT_LEVEL));
        for (int i=0;i<10;i++) {
            clock[0] += .125;
            restored.endGame((short)3, true);restored.endGame((short)4, false);
            assertEquals(saved, restored.captureState());assertTrue(after.isEmpty());
            restored = GameResultController.restoreState(roundTrip(restored.captureState()), () -> clock[0], service(after), List.of((short)3,(short)4,(short)5));
        }
        restored.endGame((short)5,true);
        assertEquals(List.of(List.of(true,(short)5,101.125)),after);
        assertEquals(List.of(List.of(false,(short)3,12.125),List.of(true,(short)4,99.875)),before);
        assertNull(restored.getResult((short)2));
        assertThrows(UnsupportedOperationException.class, () -> saved.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> saved.results().get((short)3).values().clear());
    }

    @Test void failedAndReentrantDeliveryStillRecordsOneTerminalOutcome() throws Exception {
        var holder = new GameResultController[1];int[] calls={0};
        var callback = (PlayerService)Proxy.newProxyInstance(PlayerService.class.getClassLoader(),new Class[]{PlayerService.class},(p,m,a)->{
            assertEquals("endGame",m.getName());calls[0]++;
            holder[0].endGame((short)3,false);
            assertTrue(holder[0].getResult((short)3).isWon());
            throw new IllegalStateException("Disconnected client");
        });
        holder[0] = new GameResultController(()->3.5,callback,List.of((short)3));
        assertThrows(IllegalStateException.class,()->holder[0].endGame((short)3,true));
        holder[0].endGame((short)3,false);assertEquals(1,calls[0]);
        var restored = GameResultController.restoreState(roundTrip(holder[0].captureState()),()->10,callback,List.of((short)3));
        restored.endGame((short)3,false);assertEquals(1,calls[0]);assertEquals(3.5,restored.getResult((short)3).getElapsedSeconds());
    }

    @Test void simultaneousCompletionsDeliverOnlyOneResult() throws Exception {
        var calls = new ArrayList<List<Object>>();var controller = new GameResultController(()->7.25,service(calls),List.of((short)3));
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var a=threads.submit(()->{ready.countDown();go.await();controller.endGame((short)3,true);return null;});
            var b=threads.submit(()->{ready.countDown();go.await();controller.endGame((short)3,false);return null;});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,calls.size());assertEquals(calls.getFirst().getFirst(),controller.getResult((short)3).isWon());
        assertEquals(controller.captureState(),roundTrip(controller.captureState()));
    }

    @Test void invalidWorldBindingsNeverDeliverOrChangeExistingOutcomes() throws Exception {
        var calls=new ArrayList<List<Object>>();var controller=new GameResultController(()->12.5,service(calls),List.of((short)3));
        controller.endGame((short)3,false);var saved=controller.captureState();calls.clear();
        for(double invalid:new double[]{12.49,-1,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->GameResultController.restoreState(saved,()->invalid,service(calls),List.of((short)3)));
        assertThrows(IllegalArgumentException.class,()->GameResultController.restoreState(saved,()->20,service(calls),List.of((short)4)));
        assertThrows(IllegalArgumentException.class,()->controller.endGame((short)4,true));
        assertEquals(saved,controller.captureState());assertTrue(calls.isEmpty());
        var empty=new ResultSnapshots.State(Map.of());assertEquals(empty,roundTrip(empty));
    }

    @Test void corruptDuplicateUnboundedAndUnsupportedValuesAreRejected() throws Exception {
        byte[] valid=ResultSnapshots.encode(new ResultSnapshots.State(Map.of((short)3,GameResult.completed(false,1.25).captureState())));
        for(int i=0;i<valid.length;i++) {byte[] truncated=Arrays.copyOf(valid,i);assertThrows(IOException.class,()->ResultSnapshots.decode(truncated));}
        assertThrows(IOException.class,()->ResultSnapshots.decode(Arrays.copyOf(valid,valid.length+1)));
        for(int offset:new int[]{0,7}) {byte[] bad=valid.clone();bad[offset]^=1;assertThrows(IOException.class,()->ResultSnapshots.decode(bad));}
        for(int size:new int[]{-1,257,Integer.MAX_VALUE}) assertThrows(IOException.class,()->ResultSnapshots.decode(raw(size,0)));
        for(int kind=1;kind<=8;kind++) {int k=kind;assertThrows(IOException.class,()->ResultSnapshots.decode(raw(1,k)));}
        assertThrows(IOException.class,()->ResultSnapshots.decode(new byte[256*1024+1]));
        assertThrows(IllegalArgumentException.class,()->new ResultSnapshots.State(Map.of((short)3,new GameResult.State(Map.of()))));
        assertThrows(IllegalArgumentException.class,()->new ResultSnapshots.State(Map.of((short)-1,GameResult.completed(true,0).captureState())));
        for(Object value:List.of("bad",true,Double.NaN,Float.POSITIVE_INFINITY,new java.math.BigInteger("1")))
            assertThrows(IllegalArgumentException.class,()->new GameResult.State(Map.of(GOLD_MINED,value)));
        assertThrows(IllegalArgumentException.class,()->new GameResult.State(Map.of(LEVEL_WON,1)));
        assertThrows(IllegalArgumentException.class,()->new GameResult.State(Map.of(TIME_TAKEN,-1d)));
    }

    private static ResultSnapshots.State roundTrip(ResultSnapshots.State state) throws Exception {
        return ResultSnapshots.decode(ResultSnapshots.encode(state));
    }
    private static PlayerService service(List<List<Object>> calls) {
        return (PlayerService)Proxy.newProxyInstance(PlayerService.class.getClassLoader(),new Class[]{PlayerService.class},(p,m,a)->{
            assertEquals("endGame",m.getName());calls.add(List.of(a));return null;
        });
    }
    private static byte[] raw(int players,int kind) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(0x4d4b4752);out.writeInt(1);out.writeInt(kind==8?2:players);
        if(kind!=0) for(int n=0;n<(kind==8?2:1);n++) {
            out.writeShort(3);out.writeInt(kind==7?Integer.MAX_VALUE:kind==1?3:2);
            SaveValues.TEXT.write(out,kind==2?"UNKNOWN":LEVEL_WON.name());out.writeByte(kind==3?255:1);out.writeByte(kind==4?2:1);
            SaveValues.TEXT.write(out,TIME_TAKEN.name());out.writeByte(7);out.writeDouble(kind==5?Double.NaN:kind==6?-1:1.25);
            if(kind==1) {SaveValues.TEXT.write(out,LEVEL_WON.name());out.writeByte(1);out.writeBoolean(false);}
        }
        return bytes.toByteArray();
    }
}
