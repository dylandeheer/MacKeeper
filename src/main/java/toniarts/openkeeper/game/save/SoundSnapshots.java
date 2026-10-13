/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.sound.SoundSnapshot;
import toniarts.openkeeper.game.sound.SoundSnapshot.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKSD v1: bounded speech queue, typed start actions, playlist cursor and active playback. */
public final class SoundSnapshots {
    public static final int MAX_BYTES=32*1024*1024;
    public static byte[] encode(SoundSnapshot state) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b5344);out.writeInt(1);out.writeShort(state.playerId());TEXT.write(out,state.levelCategory());
            BOOLEAN.write(out,state.enabled());TEXT.write(out,state.backgroundCategory());out.writeInt(state.playlist().size());
            for(var file:state.playlist()) TEXT.write(out,file);
            out.writeInt(state.nextTrack());out.writeInt(state.pending().size());for(var speech:state.pending()) writeSpeech(out,speech);
            BOOLEAN.write(out,state.speech()!=null);
            if(state.speech()!=null) {writeSpeech(out,state.speech().speech());writePlayback(out,state.speech().playback());}
            BOOLEAN.write(out,state.background()!=null);if(state.background()!=null) writePlayback(out,state.background());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static SoundSnapshot decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0x4d4b5344 || in.readInt()!=1) throw new IOException("Unsupported sound section");
            short player=in.readShort();String level=TEXT.read(in);boolean enabled=BOOLEAN.read(in);String category=TEXT.read(in);
            int n=count(in.readInt(),SoundSnapshot.MAX_ENTRIES);var playlist=new ArrayList<String>();for(int i=0;i<n;i++) playlist.add(TEXT.read(in));
            int next=in.readInt();n=count(in.readInt(),SoundSnapshot.MAX_ENTRIES);var queue=new ArrayList<Speech>();for(int i=0;i<n;i++) queue.add(readSpeech(in));
            ActiveSpeech speech=BOOLEAN.read(in)?new ActiveSpeech(readSpeech(in),readPlayback(in)):null;
            Playback background=BOOLEAN.read(in)?readPlayback(in):null;
            var state=new SoundSnapshot(player,level,enabled,category,playlist,next,queue,speech,background);
            if(in.read()!=-1) throw new IOException("Trailing sound data");return state;
        } catch(IllegalArgumentException|NullPointerException e) {throw new IOException("Invalid sound section",e);}
    }
    private static void writeSpeech(DataOutput out,Speech value) throws IOException {
        TEXT.write(out,value.category());BOOLEAN.write(out,value.global());out.writeInt(value.id());TEXT.write(out,value.file());BOOLEAN.write(out,value.start()!=null);
        if(value.start()!=null) {BOOLEAN.write(out,value.start().showText());BOOLEAN.write(out,value.start().introduction());out.writeInt(value.start().pathId());}
    }
    private static Speech readSpeech(DataInput in) throws IOException {
        String category=TEXT.read(in);boolean global=BOOLEAN.read(in);int id=in.readInt();String file=TEXT.read(in);
        return new Speech(category,global,id,file,BOOLEAN.read(in)?new StartAction(BOOLEAN.read(in),BOOLEAN.read(in),in.readInt()):null);
    }
    private static void writePlayback(DataOutput out,Playback value) throws IOException {
        TEXT.write(out,value.file());TEXT.write(out,value.fingerprint());FLOAT.write(out,value.seconds());enumeration(Status.class).write(out,value.status());FLOAT.write(out,value.volume());
    }
    private static Playback readPlayback(DataInput in) throws IOException {return new Playback(TEXT.read(in),TEXT.read(in),FLOAT.read(in),enumeration(Status.class).read(in),FLOAT.read(in));}
    private SoundSnapshots() {}
}
