/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.sound;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.save.SoundSnapshots;
import toniarts.openkeeper.game.sound.SoundSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class SoundSnapshotsTest {
    private final String first="Sounds/Level1/first.wav", second="Sounds/Level1/second.wav", music="Sounds/Global/music.wav";
    private Speech speech(int id,StartAction action) {return new Speech("speech_level1",false,id,id==1?first:second,action);}
    private Playback playing(String file,Status status) {return new Playback(file,"a".repeat(64),1.75f,status,0.4f);}
    private SoundSnapshot sample(boolean enabled) {
        var status=enabled?Status.PLAYING:Status.PAUSED;
        return new SoundSnapshot((short)3,"speech_level1",enabled,"MUSIC",List.of(music,"Sounds/Global/next.wav"),1,
                List.of(speech(2,new StartAction(true,true,Integer.MIN_VALUE)),speech(2,null)),new ActiveSpeech(speech(1,new StartAction(false,false,0)),playing(first,status)),playing(music,status));
    }
    @Test void repeatedRoundTripsRetainNonzeroPlaybackAndTypedPendingActions() throws Exception {
        for(boolean enabled:List.of(false,true)) {
            var source=sample(enabled);var copy=source;
            for(int n=0;n<4;n++) {copy=SoundSnapshots.decode(SoundSnapshots.encode(copy));assertEquals(source,copy);}
            assertEquals(1,copy.nextTrack());assertEquals(1.75f,copy.speech().playback().seconds());
            assertNotNull(copy.pending().getFirst().start());assertNull(copy.pending().getLast().start());
        }
    }
    @Test void idleChannelsAndMapsWithoutLevelSpeechRemainDistinct() throws Exception {
        var state=new SoundSnapshot((short)255,"",false,"AMBIENCE",List.of(music),0,List.of(),null,null);
        assertEquals(state,SoundSnapshots.decode(SoundSnapshots.encode(state)));
        var mentor=new Speech("SPEECH_MENTOR",true,12,first,null);
        var queued=new SoundSnapshot((short)0,"",true,"OPTIONS",List.of(music),0,List.of(mentor),null,null);
        assertEquals(queued,SoundSnapshots.decode(SoundSnapshots.encode(queued)));
    }
    @Test void snapshotsDetachCollectionsAndRejectInconsistentChannelsAndPaths() {
        var playlist=new ArrayList<>(List.of(music));var queue=new ArrayList<>(List.of(speech(1,null)));
        var state=new SoundSnapshot((short)3,"speech_level1",true,"MUSIC",playlist,0,queue,null,null);
        playlist.clear();queue.clear();assertEquals(1,state.pending().size());assertEquals(1,state.playlist().size());
        assertThrows(UnsupportedOperationException.class,()->state.playlist().clear());
        assertThrows(IllegalArgumentException.class,()->new ActiveSpeech(speech(1,null),playing(second,Status.PLAYING)));
        assertThrows(IllegalArgumentException.class,()->new SoundSnapshot((short)3,"x",false,"MUSIC",List.of(music),0,List.of(),null,playing(music,Status.PLAYING)));
        assertThrows(IllegalArgumentException.class,()->new SoundSnapshot((short)3,"x",true,"MUSIC",List.of(music),1,List.of(),null,null));
        assertThrows(IllegalArgumentException.class,()->new SoundSnapshot((short)3,"x",true,"MUSIC",List.of(music),0,List.of(),null,playing(first,Status.PLAYING)));
        for(String path:List.of("/abs.wav","../outside.wav","Sounds/../outside.wav","C:/outside.wav","Sounds//empty.wav","Sounds/./file.wav","Sounds\\file.wav"))
            assertThrows(IllegalArgumentException.class,()->playing(path,Status.PLAYING));
        for(float value:new float[]{-1,Float.NaN,Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->new Playback(first,"a".repeat(64),value,Status.PAUSED,1));
            assertThrows(IllegalArgumentException.class,()->new Playback(first,"a".repeat(64),0,Status.PAUSED,value));
        }
    }
    @Test void malformedSectionsNeverDecodeAsPartialState() throws Exception {
        byte[] bytes=SoundSnapshots.encode(sample(true));
        for(int n=0;n<bytes.length;n++) {final int size=n;assertThrows(IOException.class,()->SoundSnapshots.decode(Arrays.copyOf(bytes,size)));}
        assertThrows(IOException.class,()->SoundSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        for(int offset:new int[]{0,4,10}) {byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->SoundSnapshots.decode(bad));}
        byte[] bad=bytes.clone();bad[14+"speech_level1".length()]=2;assertThrows(IOException.class,()->SoundSnapshots.decode(bad));
        assertThrows(IOException.class,()->SoundSnapshots.decode(new byte[SoundSnapshots.MAX_BYTES+1]));
    }
}
