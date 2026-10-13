/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import com.jme3.audio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.Settings;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.LocalGameSession;
import toniarts.openkeeper.game.sound.*;
import toniarts.openkeeper.game.sound.SoundSnapshot.*;
import toniarts.openkeeper.game.save.SoundSnapshots;
import toniarts.openkeeper.tools.modelviewer.SoundsLoader;
import toniarts.openkeeper.view.SystemMessageState;

/** Owned audio backend continuation, independent of full saved-session assembly. */
class NativeAudioSmoke {
    static volatile boolean successful;
    public static void main(String[] args) throws Exception {
        Thread.ofPlatform().daemon().name("audio-watchdog").start(()->{
            try{Thread.sleep(180_000);}catch(InterruptedException ignored){return;}
            System.err.println("AssertionError: Native audio timed out");System.exit(1);
        });
        var parse=Main.class.getDeclaredMethod("parseArguments",String[].class);parse.setAccessible(true);
        parse.invoke(null,(Object)new String[]{"-nomovies","-level","Level1"});
        var constructor=Main.class.getDeclaredConstructor();constructor.setAccessible(true);Main app=constructor.newInstance();
        var init=Main.class.getDeclaredMethod("initSettings",Main.class);init.setAccessible(true);init.invoke(null,app);
        app.setShowSettings(false);app.setPauseOnLostFocus(false);app.getStateManager().attach(new Probe());app.start();
        if(!successful) throw new AssertionError("Native audio did not complete");
    }
    static class Probe extends AbstractAppState {
        Main app;AppStateManager states;GameClientState client;GameLoopManager loop;SoundState sound;int stage,messages,first;float delay;SoundSnapshot paused;
        @Override public void initialize(AppStateManager manager,Application application){super.initialize(manager,application);
            application.getListener().setVolume(0); // Keep native playback running without audible test output.
states=manager;app=(Main)application;}
        int count(){return states.getState(SystemMessageState.class).captureState().messages().size();}
        SoundSnapshot with(SoundSnapshot s,boolean enabled,List<Speech> pending,ActiveSpeech speech,Playback background){return new SoundSnapshot(s.playerId(),s.levelCategory(),enabled,s.backgroundCategory(),s.playlist(),s.nextTrack(),pending,speech,background);}
        Playback at(Playback p,float seconds){return new Playback(p.file(),p.fingerprint(),seconds,p.status(),p.volume());}
        float duration(String file){return new AudioNode(app.getAssetManager(),file,AudioData.DataType.Buffer).getAudioData().getDuration();}
        void equivalent(SoundSnapshot a,SoundSnapshot b) {
            if(!a.pending().equals(b.pending()) || a.playerId()!=b.playerId() || a.enabled()!=b.enabled() || !a.playlist().equals(b.playlist()) || a.nextTrack()!=b.nextTrack()) throw new AssertionError("Audio queue/cursor mismatch");
            if((a.speech()==null)!=(b.speech()==null) || (a.background()==null)!=(b.background()==null)) throw new AssertionError("Audio channel mismatch");
            if(a.speech()!=null){if(!a.speech().speech().equals(b.speech().speech()))throw new AssertionError("Active speech changed");equivalent(a.speech().playback(),b.speech().playback());}
            if(a.background()!=null)equivalent(a.background(),b.background());
        }
        void equivalent(Playback a,Playback b){if(!a.file().equals(b.file())||!a.fingerprint().equals(b.fingerprint())||a.status()!=b.status()||a.volume()!=b.volume()||Math.abs(a.seconds()-b.seconds())>0.04f)throw new AssertionError("Audio seek/status mismatch: "+a+" / "+b);}
        @Override public void update(float tpf) {
            try {
                var player=states.getState(PlayerState.class);
                if(stage==0 && player!=null && player.isInitialized() && player.isEnabled()) {
                    if((delay+=tpf)<4)return;
                    client=states.getState(GameClientState.class);sound=states.getState(SoundState.class);
                    var field=GameServerState.class.getDeclaredField("game");field.setAccessible(true);loop=(GameLoopManager)field.get(states.getState(GameServerState.class));
                    loop.pause();stage=1;delay=0;
                }
                if(stage==1 && player.isPaused() && !((LocalGameSession)client.getGameClientService()).isInTransition() && client.capturePendingPresentation().events().isEmpty()) {
                    Main.getUserSettings().setSetting(Settings.Setting.VOICE_ENABLED,true);Main.getUserSettings().setSetting(Settings.Setting.MUSIC_ENABLED,true);
                    sound.setEnabled(false);var original=sound.captureState();sound.restoreState(with(original,false,List.of(),null,null));
                    sound.changeBackground(SoundState.Background.MUSIC);
                    first=SoundsLoader.load(original.levelCategory(),false).getGroups().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .filter(e->!e.getValue().getFiles().isEmpty())
                        .filter(e->duration("Sounds/"+e.getValue().getFiles().getFirst().getFilename().replace('\\','/'))>3).findFirst().orElseThrow().getKey();
                    states.getState(SystemMessageState.class).setEnabled(true);messages=count();
                    sound.attachLevelSpeech(first,new StartAction(false,false,0));sound.attachLevelSpeech(first,new StartAction(false,true,123));
                    var queued=sound.captureState();if(queued.pending().size()!=2)throw new AssertionError("Speech not queued");
                    sound.restoreState(SoundSnapshots.decode(SoundSnapshots.encode(queued)));if(count()!=messages)throw new AssertionError("Queue restore replayed start");
                    sound.setEnabled(true);stage=2;delay=0;
                }
                if(stage==2 && (delay+=tpf)>0.65f) {
                    sound.setEnabled(false);paused=sound.captureState();
                    if(paused.speech()==null || paused.background()==null || paused.speech().playback().seconds()<0.2f || paused.background().seconds()<0.2f || paused.pending().size()!=1 || count()!=messages+1)throw new AssertionError("Real audio did not start once: "+paused);
                    Path file=Files.createTempFile(Path.of("work"),"audio-",".bin");
                    try{for(int n=0;n<3;n++){Files.write(file,SoundSnapshots.encode(paused));sound.restoreState(SoundSnapshots.decode(Files.readAllBytes(file)));equivalent(paused,sound.captureState());if(count()!=messages+1)throw new AssertionError("Active restore replayed message");}}finally{Files.delete(file);}
                    var p=paused.speech().playback();var bad=new Playback(p.file(),"0".repeat(64),p.seconds(),p.status(),p.volume());
                    try{sound.restoreState(with(paused,false,paused.pending(),new ActiveSpeech(paused.speech().speech(),bad),paused.background()));throw new AssertionError("Changed audio samples accepted");}catch(IllegalArgumentException expected){}
                    equivalent(paused,sound.captureState());
                    try(var worker=Executors.newSingleThreadExecutor()){worker.submit(()->{try{sound.captureState();throw new AssertionError("Worker capture accepted");}catch(IllegalStateException expected){}}).get(5,TimeUnit.SECONDS);}
                    stage=3;delay=0;
                }
                if(stage==3 && (delay+=tpf)>0.5f) {equivalent(paused,sound.captureState());sound.setEnabled(true);stage=4;delay=0;}
                if(stage==4 && (delay+=tpf)>0.45f) {
                    var advanced=sound.captureState();if(advanced.speech()==null || advanced.speech().playback().seconds()<paused.speech().playback().seconds()+0.15f)throw new AssertionError("Restored audio did not advance");
                    sound.setEnabled(false);var ending=sound.captureState();
                    var speech=new ActiveSpeech(ending.speech().speech(),at(ending.speech().playback(),duration(ending.speech().playback().file())-0.12f));
                    var background=at(ending.background(),duration(ending.background().file())-0.12f);
                    paused=with(ending,false,ending.pending(),speech,background);sound.restoreState(paused);sound.setEnabled(true);stage=5;delay=0;
                }
                if(stage==5 && (delay+=tpf)>0.8f) {
                    var continued=sound.captureState();
                    if(!continued.pending().isEmpty() || continued.speech()==null || !continued.speech().speech().start().introduction() || count()!=messages+2
                        || continued.background()==null || !continued.background().file().equals(paused.playlist().get(paused.nextTrack()))
                        || continued.nextTrack()!=(paused.nextTrack()+1)%paused.playlist().size())throw new AssertionError("Continuation failed queue/playlist/start effects: "+continued);
                    sound.unbindSession(null);if(sound.captureState().speech()==null)throw new AssertionError("Stale unbind stopped current speech");
                    var clipField=SoundState.class.getDeclaredField("speechNode");clipField.setAccessible(true);var clip=clipField.get(sound);
                    var nodeMethod=clip.getClass().getDeclaredMethod("node");nodeMethod.setAccessible(true);var oldNode=(AudioNode)nodeMethod.invoke(clip);
                    player.quitToMainMenu();
                    if(oldNode.getStatus()!=AudioSource.Status.Stopped)throw new AssertionError("Old speech survived actual session teardown");
                    try{sound.captureState();throw new AssertionError("Detached session can capture audio");}catch(IllegalStateException expected){}
                    try {sound.bindSession(client,client.getLevelData(),(short)3);throw new AssertionError("Detached session rebound audio");}
                    catch(IllegalArgumentException expected) {}
                    System.out.println("AUDIO PASS real speech/music nonzero seek across 3 disk restores, paused stability, resumed continuation, queued start exactly once, playlist advancement, changed-sample rejection and detached-session rejection");
                    stage=6;delay=0;
                }
                if(stage==6 && app.getNifty().getCurrentScreen()!=null
                        && app.getNifty().getCurrentScreen().getScreenId().equals(MainMenuScreenController.SCREEN_START_ID) && (delay+=tpf)>2) {
                    successful=true;app.stop();stage=99;
                }
            } catch(Throwable failure){failure.printStackTrace();app.stop();stage=99;}
        }
    }
}
