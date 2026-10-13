/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.state;

import com.jme3.app.Application;
import com.jme3.app.state.AppStateManager;
import com.jme3.audio.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.Settings;
import toniarts.openkeeper.game.sound.*;
import toniarts.openkeeper.game.sound.SoundSnapshot.*;
import toniarts.openkeeper.tools.convert.AssetsConverter;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.modelviewer.SoundsLoader;

/** Render-thread audio owner. Queued speech contains data, never callbacks from an old session. */
public final class SoundState extends AbstractPauseAwareState {
    public enum Background {
        AMBIENCE, OPTIONS, MUSIC, ONE_SHOT_ATMOS;
        public String getName() {return name();}
    }
    private static final Logger logger=System.getLogger(SoundState.class.getName());
    private Main app;
    private AppStateManager stateManager;
    private Thread renderThread;
    private GameClientState owner;
    private short playerId;
    private String levelCategory;
    private Background backgroundCategory=Background.MUSIC;
    private List<String> playlist=List.of();
    private int nextTrack;
    private final ArrayDeque<Speech> speechQueue=new ArrayDeque<>();
    private Speech activeSpeech;
    private Clip speechNode,backgroundNode;
    private record Clip(String file, String fingerprint, AudioNode node) {}

    public SoundState(boolean enabled) {super.setEnabled(enabled);}
    @Override public void initialize(AppStateManager manager,Application application) {
        super.initialize(manager,application);app=(Main)application;stateManager=manager;renderThread=Thread.currentThread();
        playlist=loadPlaylist(backgroundCategory);
    }
    @Override public boolean isPauseable() {return true;}
    private void requireThread() {
        if(!isInitialized() || Thread.currentThread()!=renderThread || stateManager.getState(SoundState.class)!=this)
            throw new IllegalStateException("Audio requires the active render-thread owner");
    }
    private void requireSession() {
        requireThread();
        if(owner==null || stateManager.getState(GameClientState.class)!=owner) throw new IllegalStateException("Audio session is not bound");
    }
    @Override public void setEnabled(boolean enabled) {
        if(isInitialized()) requireThread();
        super.setEnabled(enabled);
        if(isInitialized()) {setPlaying(speechNode,enabled);setPlaying(backgroundNode,enabled);}
    }
    private static void setPlaying(Clip clip,boolean playing) {
        if(clip!=null && clip.node().getStatus()!=AudioSource.Status.Stopped) {
            if(playing) clip.node().play();else clip.node().pause();
        }
    }
    /** Called after guarded client startup reaches the render thread. */
    public void bindSession(GameClientState session,IKwdFile level,short id) {
        requireThread();Objects.requireNonNull(session);
        if(stateManager.getState(GameClientState.class)!=session || id<0 || id>255) throw new IllegalArgumentException("Inactive audio session");
        String category=Objects.requireNonNullElse(level.getGameLevel().getSoundCategory(),"");
        if(!category.isEmpty()) SoundSnapshot.category(category);
        clearSpeech();owner=session;playerId=id;levelCategory=category;
    }
    public void unbindSession(GameClientState session) {
        requireThread();
        if(owner!=session) return;
        clearSpeech();owner=null;levelCategory=null;
    }
    private void clearSpeech() {stop(speechNode);speechNode=null;activeSpeech=null;speechQueue.clear();}
    private static void stop(Clip clip) {if(clip!=null) clip.node().stop();}
    public void changeBackground(Background category) {
        requireThread();var prepared=loadPlaylist(Objects.requireNonNull(category));
        stop(backgroundNode);backgroundNode=null;backgroundCategory=category;playlist=prepared;nextTrack=0;
    }
    private static List<String> loadPlaylist(Background category) {
        SoundCategory source=SoundsLoader.load(category.getName(),true);
        if(source==null) throw new IllegalArgumentException("Missing audio category "+category);
        var result=new ArrayList<String>();
        source.getGroups().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(group -> {
            for(var file:group.getValue().getFiles()) result.add(assetPath(file.getFilename()));
        });
        if(result.isEmpty() || result.size()>SoundSnapshot.MAX_ENTRIES) throw new IllegalArgumentException("Invalid audio playlist");
        return List.copyOf(result);
    }
    private static String assetPath(String file) {
        String result=(AssetsConverter.SOUNDS_FOLDER+"/"+file).replace('\\','/');SoundSnapshot.file(result);return result;
    }
    private static String speechFile(String category,boolean global,int id) {
        var source=SoundsLoader.load(category,global);
        var group=source==null?null:source.getGroup(id);
        if(group==null || group.getFiles().isEmpty()) throw new IllegalArgumentException("Missing speech "+category+"/"+id);
        return assetPath(group.getFiles().getFirst().getFilename());
    }
    public void attachLevelSpeech(int id,StartAction action) {requireSession();attachSpeech(levelCategory,false,id,action);}
    public void attachMentorSpeech(MentorType type) {requireSession();attachSpeech(SoundCategory.SPEECH_MENTOR,true,type.getId(),null);}
    private void attachSpeech(String category,boolean global,int id,StartAction action) {
        if(!Main.getUserSettings().getBoolean(Settings.Setting.VOICE_ENABLED)) return;
        try {
            var speech=new Speech(category,global,id,speechFile(category,global,id),action);
            if(speechQueue.size()>=SoundSnapshot.MAX_ENTRIES) throw new IllegalStateException("Speech queue is full");
            speechQueue.add(speech);
        } catch(RuntimeException e) {logger.log(Level.WARNING,"Failed to attach speech "+category+"/"+id,e);}
    }
    public void stopSpeech() {requireThread();stop(speechNode);speechNode=null;activeSpeech=null;}
    private Clip loadClip(String file,float volume) {
        var node=new AudioNode(app.getAssetManager(),file,AudioData.DataType.Buffer);
        node.setLooping(false);node.setPositional(false);node.setVolume(volume);
        var buffer=(AudioBuffer)node.getAudioData();
        if(!Float.isFinite(buffer.getDuration()) || buffer.getDuration()<=0) throw new IllegalArgumentException("Empty audio clip");
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(12).putInt(buffer.getSampleRate()).putInt(buffer.getChannels()).putInt(buffer.getBitsPerSample()).array());
            digest.update(buffer.getData().duplicate().rewind());
            return new Clip(file,HexFormat.of().formatHex(digest.digest()),node);
        } catch(NoSuchAlgorithmException e) {throw new AssertionError(e);}
    }
    private static boolean finished(Clip clip) {return clip==null || clip.node().getStatus()==AudioSource.Status.Stopped;}
    private static float volume(Settings.Setting kind) {
        return Main.getUserSettings().getFloat(Settings.Setting.MASTER_VOLUME)*Main.getUserSettings().getFloat(kind);
    }
    @Override public void update(float tpf) {
        if(!isInitialized() || !isEnabled()) return;
        if(owner!=null && stateManager.getState(GameClientState.class)!=owner) {clearSpeech();owner=null;levelCategory=null;}
        if(!speechQueue.isEmpty() && finished(speechNode)) {
            Speech next=speechQueue.peek();Clip prepared=loadClip(next.file(),volume(Settings.Setting.VOICE_VOLUME));
            speechQueue.remove();speechNode=prepared;activeSpeech=next;speechNode.node().play();
            if(next.start()!=null && owner!=null) owner.onSpeechStarted(next.id(),next.start());
        }
        if(finished(backgroundNode) && Main.getUserSettings().getBoolean(Settings.Setting.MUSIC_ENABLED)) {
            Clip prepared=loadClip(playlist.get(nextTrack),volume(Settings.Setting.MUSIC_VOLUME));
            backgroundNode=prepared;nextTrack=(nextTrack+1)%playlist.size();backgroundNode.node().play();
        }
    }
    private static Playback capture(Clip clip) {
        if(clip==null) return null;
        float seconds=clip.node().getPlaybackTime();var status=clip.node().getStatus();
        if(status==AudioSource.Status.Stopped || seconds>=clip.node().getAudioData().getDuration()) return null;
        return new Playback(clip.file(),clip.fingerprint(),seconds,status==AudioSource.Status.Playing?Status.PLAYING:Status.PAUSED,clip.node().getVolume());
    }
    public SoundSnapshot captureState() {
        requireSession();var speech=capture(speechNode);
        return new SoundSnapshot(playerId,levelCategory,isEnabled(),backgroundCategory.name(),playlist,nextTrack,List.copyOf(speechQueue),
                speech==null?null:new ActiveSpeech(activeSpeech,speech),capture(backgroundNode));
    }
    private void validateSpeech(Speech speech) {
        if(speech.global()?(!speech.category().equals(SoundCategory.SPEECH_MENTOR)||speech.start()!=null):!speech.category().equals(levelCategory))
            throw new IllegalArgumentException("Speech belongs to another level/category");
        if(!speech.file().equals(speechFile(speech.category(),speech.global(),speech.id()))) throw new IllegalArgumentException("Speech file changed");
    }
    private Clip prepare(Playback playback) {
        if(playback==null) return null;
        Clip prepared=loadClip(playback.file(),playback.volume());
        if(!prepared.fingerprint().equals(playback.fingerprint()) || playback.seconds()>=prepared.node().getAudioData().getDuration())
            throw new IllegalArgumentException("Audio samples or playback bounds changed");
        prepared.node().setTimeOffset(playback.seconds());return prepared;
    }
    /** Validates and loads both clips before stopping live audio. Active start actions are never replayed. */
    public void restoreState(SoundSnapshot state) {
        requireSession();
        if(state.playerId()!=playerId || !state.levelCategory().equals(levelCategory)) throw new IllegalArgumentException("Audio belongs to another player/level");
        var category=Background.valueOf(state.backgroundCategory());var installed=loadPlaylist(category);
        if(!installed.equals(state.playlist())) throw new IllegalArgumentException("Audio playlist changed");
        state.pending().forEach(this::validateSpeech);
        if(state.speech()!=null) validateSpeech(state.speech().speech());
        var speech=prepare(state.speech()==null?null:state.speech().playback());var background=prepare(state.background());
        stop(speechNode);stop(backgroundNode);
        speechQueue.clear();speechQueue.addAll(state.pending());activeSpeech=state.speech()==null?null:state.speech().speech();
        speechNode=speech;backgroundNode=background;backgroundCategory=category;playlist=installed;nextTrack=state.nextTrack();
        super.setEnabled(state.enabled());
        activate(speech,state.speech()==null?null:state.speech().playback());activate(background,state.background());
    }
    private static void activate(Clip clip,Playback playback) {
        if(clip==null) return;
        clip.node().play();if(playback.status()==Status.PAUSED) clip.node().pause();
    }
    @Override public void cleanup() {
        clearSpeech();stop(backgroundNode);backgroundNode=null;owner=null;levelCategory=null;
        super.cleanup();
    }
}
