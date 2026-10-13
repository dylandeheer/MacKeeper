/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.view.WorldViewState;
import toniarts.openkeeper.view.WorldViewState.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class WorldViewSnapshotsTest {
    static WorldViewState sample() {
        return new WorldViewState((short)3,8,4,new Flash(false,1.75f,true,Set.of(new Point(1,2),new Point(3,1)),Set.of(new Point(1,2))),
                Map.of(new EntityId(1),new Flower(Kind.CREATURE,(short)2,true,18,2.5f,new Cycle(Status.REASON,.25f,.09f)),
                       new EntityId(2),new Flower(Kind.DOOR,(short)1,false,4,4,null),
                       new EntityId(3),new Flower(Kind.TRAP,(short)4,true,1,7,null)));
    }
    @Test void canonicalRoundTripsKeepAllKindsClocksAndImmutableMembership()throws Exception {
        var expected=sample();var saved=expected;var bytes=WorldViewSnapshots.encode(saved);
        for(int i=0;i<5;i++){saved=WorldViewSnapshots.decode(bytes);assertEquals(expected,saved);assertArrayEquals(bytes,WorldViewSnapshots.encode(saved));}
        assertThrows(UnsupportedOperationException.class,()->expected.flash().points().clear());
        assertThrows(UnsupportedOperationException.class,()->expected.flowers().clear());
        for(Status status:Status.values()) {
            var flower=new Flower(Kind.CREATURE,(short)1,false,.2f,4,new Cycle(status,0,0));
            var state=new WorldViewState((short)3,8,4,expected.flash(),Map.of(new EntityId(0),flower));
            assertEquals(state,WorldViewSnapshots.decode(WorldViewSnapshots.encode(state)));
        }
    }
    @Test void malformedAndTruncatedSectionsCannotInstallInvalidTimingsMembershipOrOwners()throws Exception {
        var bytes=WorldViewSnapshots.encode(sample());
        for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->WorldViewSnapshots.decode(truncated));}
        assertThrows(IOException.class,()->WorldViewSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        for(int offset:new int[]{0,4,10,14,24}) {
            var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,Integer.MAX_VALUE);assertThrows(IOException.class,()->WorldViewSnapshots.decode(bad));
        }
        var bad=bytes.clone();bad[18]=2;assertThrows(IOException.class,()->WorldViewSnapshots.decode(bad));
        var duplicate=bytes.clone();System.arraycopy(duplicate,28,duplicate,36,8);
        assertThrows(IOException.class,()->WorldViewSnapshots.decode(duplicate));
        var nan=bytes.clone();ByteBuffer.wrap(nan).putFloat(19,Float.NaN);assertThrows(IOException.class,()->WorldViewSnapshots.decode(nan));
        assertThrows(IllegalArgumentException.class,()->new Flash(true,0,false,Set.of(new Point(1,1)),Set.of(new Point(1,1))));
        assertThrows(IllegalArgumentException.class,()->new Flash(true,0,true,Set.of(),Set.of(new Point(1,1))));
        assertThrows(IllegalArgumentException.class,()->new Flower(Kind.DOOR,(short)1,true,0,1,new Cycle(Status.LEVEL,0,0)));
        assertThrows(IllegalArgumentException.class,()->new Flower(Kind.CREATURE,(short)1,true,0,1,null));
        assertThrows(IllegalArgumentException.class,()->new WorldViewState((short)3,1,1,sample().flash(),Map.of()));
    }
}
