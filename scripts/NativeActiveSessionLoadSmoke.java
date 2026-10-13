/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.sound.SoundSnapshot;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.utils.AppPaths;
import toniarts.openkeeper.view.*;
import toniarts.openkeeper.view.selection.SelectionHandler;
/** Real active-session handoffs, recovery write rejection, activation failure and reconstructed rollback. */
class NativeActiveSessionLoadSmoke {
 static boolean successful;
 static Object field(Object owner,Class<?> type,String name)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(240_000);}catch(InterruptedException e){return;}System.err.println("AssertionError: Active session load timed out");System.exit(1);});
  var parse=Main.class.getDeclaredMethod("parseArguments",String[].class);parse.setAccessible(true);parse.invoke(null,(Object)new String[]{"-nomovies"});
  var ctor=Main.class.getDeclaredConstructor();ctor.setAccessible(true);Main app=ctor.newInstance();
  var init=Main.class.getDeclaredMethod("initSettings",Main.class);init.setAccessible(true);init.invoke(null,app);
  app.setShowSettings(false);app.setPauseOnLostFocus(false);app.getStateManager().attach(new Probe());app.start();
  if(!successful)throw new AssertionError("Native active session load did not complete");
 }
 static class Probe extends AbstractAppState {
  Main app;AppStateManager states;int stage;float delay;boolean sawHold;
  LocalSessionArchive saved;GameClientState original;CompletableFuture<LocalSessionLoader.Result> operation;
  final Path good=Path.of("work/native-active-good.mkls"),bad=Path.of("work/native-active-bad.mkls");
  Path recoveryFolder;byte[] expectedServer;double resumedAt;
  @Override public void initialize(AppStateManager manager,Application application){super.initialize(manager,application);
            application.getListener().setVolume(0); // Keep native playback running without audible test output.
states=manager;app=(Main)application;}
  void next(int value){stage=value;delay=0;System.out.println("ACTIVE stage="+value);}
  @Override public void update(float tpf){try{
   delay+=tpf;var client=states.getState(GameClientState.class);var player=states.getState(PlayerState.class);var menu=states.getState(MainMenuState.class);
   if(stage==0){
    if(menu==null||!menu.isInitialized()||app.getNifty().getCurrentScreen()==null||!app.getNifty().getCurrentScreen().getScreenId().equals(MainMenuScreenController.SCREEN_START_ID))return;
    menu.setEnabled(false);LocalGameSession.createLocalGame("Level1",true,states,app,new CampaignLevel(CampaignLevel.LevelType.Level,1));next(1);return;
   }
   if(stage==1){
    if(client==null||!client.loadingCompletion().toCompletableFuture().isDone()||delay<8)return;
    client.loadingCompletion().toCompletableFuture().join();player.setPaused(true);next(2);return;
   }
   if(stage==2){
    if(!player.isPaused()||delay<.2)return;
    original=client;saved=states.getState(GameServerState.class).captureLocalSession(client);expectedServer=SimulationArchives.encode(saved.server());
    LocalSessionArchives.write(good,saved);Files.write(bad,new byte[]{1,2,3});
    operation=LocalSessionLoader.load(app,bad).toCompletableFuture();next(3);return;
   }
   if(stage==3){
    if(!operation.isDone())return;var outcome=operation.join();
    check(!outcome.loaded()&&!outcome.recovered()&&outcome.recoveryFile()==null,"Invalid input was not rejected before retirement");sameOriginal(client);
    System.out.println("ACTIVE corrupt file rejected with the live session unchanged");
    operation=LocalSessionLoader.load(app,good).toCompletableFuture();
    app.enqueue(()->states.detach(states.getState(LocalSessionLoader.class)));next(4);return;
   }
   if(stage==4){
    if(!operation.isDone())return;check(operation.isCompletedExceptionally(),"Early cancellation did not complete");sameOriginal(client);
    System.out.println("ACTIVE pre-initialize cancellation closes completion without touching the live session");
    recoveryFolder=AppPaths.dataDirectory().resolve("Saves/Recovery");Files.createDirectories(recoveryFolder.getParent());Files.write(recoveryFolder,new byte[]{7});
    var camera=states.getState(PlayerCameraState.class);
    camera.onAction((String)field(null,PlayerCameraState.class,"SPECIAL_KEY_CONTROL"),true,0);
    ((SelectionHandler)field(states.getState(PlayerInteractionState.class),PlayerInteractionState.class,"selectionHandler")).setActive(true);
    operation=LocalSessionLoader.load(app,good).toCompletableFuture();next(5);return;
   }
   if(stage==5){
    if(!client.isAcceptingInput()){
     sawHold=true;check(!((LocalGameSession)client.getGameClientService()).isCommandAdmissionEnabled(),"Held server admits commands");
     var camera=states.getState(PlayerCameraState.class);camera.onAction((String)field(null,PlayerCameraState.class,"SPECIAL_KEY_CONTROL"),false,0);
    }
    if(!operation.isDone())return;var outcome=operation.join();check(!outcome.loaded()&&!outcome.recovered()&&sawHold,"Recovery write failure was not exercised");sameOriginal(client);
    check(((Set<?>)field(states.getState(PlayerCameraState.class),PlayerCameraState.class,"keys")).isEmpty(),"Abort retained camera modifiers");
    check(!((SelectionHandler)field(states.getState(PlayerInteractionState.class),PlayerInteractionState.class,"selectionHandler")).isActive(),"Abort retained an unfinished drag");
    check(Arrays.equals(expectedServer,SimulationArchives.encode(states.getState(GameServerState.class).captureLocalSession(client).server())),"Rejected recovery write advanced the paused world");
    Files.delete(recoveryFolder);System.out.println("ACTIVE recovery write failure retains original world, resumes admission and clears stale input");
    operation=LocalSessionLoader.load(app,good).toCompletableFuture();next(6);return;
   }
   if(stage==6){
    if(!operation.isDone())return;var outcome=operation.join();
    check(outcome.loaded()&&!outcome.recovered()&&Files.isRegularFile(outcome.recoveryFile()),"Successful replacement has no recovery file");
    check(client!=original&&client.isAcceptingInput(),"Replacement reused old client or retained input hold");
    check(Arrays.equals(expectedServer,SimulationArchives.encode(states.getState(GameServerState.class).captureLocalSession(client).server())),"Replacement changed saved paused world");
    check(LocalSessionArchives.read(outcome.recoveryFile()).client().progress().attemptId().equals(saved.client().progress().attemptId()),"Recovery file lost original attempt");
    clearInput();System.out.println("ACTIVE loaded save into fresh owners after a synced recovery checkpoint");
    original=client;var c=saved.client();var sound=c.sound();check(sound.background()!=null,"No actual background clip for failure test");
    var p=sound.background();var wrong=new SoundSnapshot.Playback(p.file(),"0".repeat(64),p.seconds(),p.status(),p.volume());
    var corrupt=new SoundSnapshot(sound.playerId(),sound.levelCategory(),sound.enabled(),sound.backgroundCategory(),sound.playlist(),sound.nextTrack(),sound.pending(),sound.speech(),wrong);
    var badClient=new ClientState(c.model(),c.progress(),c.screen(),c.fog(),corrupt,c.worldView(),c.active());
    LocalSessionArchives.write(bad,new LocalSessionArchive(saved.campaign(),saved.server(),badClient));
    operation=LocalSessionLoader.load(app,bad).toCompletableFuture();next(7);return;
   }
   if(stage==7){
    if(!operation.isDone())return;var outcome=operation.join();
    check(!outcome.loaded()&&outcome.recovered()&&outcome.error()!=null,"Activation failure did not reconstruct the prior world");
    check(client!=original&&client.isAcceptingInput(),"Rollback reused retired owner or blocked input");clearInput();
    var recovery=LocalSessionArchives.read(outcome.recoveryFile());
    check(Arrays.equals(SimulationArchives.encode(recovery.server()),SimulationArchives.encode(states.getState(GameServerState.class).captureLocalSession(client).server())),"Rollback changed the paused recovery world");
    check(client.captureClientState().progress().equals(recovery.client().progress()),"Rollback lost progress identity");
    System.out.println("ACTIVE invalid decoded audio fingerprint fails activation; prior world and progress reconstructed from recovery");
    resumedAt=recovery.server().simulation().level().clock().elapsedSeconds();player.setPaused(false);next(8);return;
   }
   if(stage==8){
    if(player.isPaused()||delay<1)return;
    check(states.getState(GameServerState.class).captureLocalSession(client).server().simulation().level().clock().elapsedSeconds()>resumedAt,"Recovered world failed to resume");
    player.quitToMainMenu();next(9);return;
   }
   if(stage==9){
    if(client!=null||player!=null||app.getNifty().getScreen("hud")!=null||delay<.5)return;
    operation=LocalSessionLoader.load(app,good).toCompletableFuture();next(10);return;
   }
   if(stage==10){
    if(!operation.isDone())return;var outcome=operation.join();check(outcome.loaded()&&outcome.recoveryFile()==null,"Menu load unexpectedly needed a prior-world recovery");clearInput();
    System.out.println("ACTIVE PASS invalid input, early cancellation, failed recovery write, active replacement, activation rollback, resumed play and menu loading");successful=true;app.stop();
   }
  }catch(Throwable failure){failure.printStackTrace();app.stop();}}
  void sameOriginal(GameClientState client){check(client==original&&client.isAcceptingInput()&&((LocalGameSession)client.getGameClientService()).isCommandAdmissionEnabled(),"Failure changed or disabled original session");clearInput();}
  void clearInput(){check(!app.getNifty().isIgnoreMouseEvents()&&!app.getNifty().isIgnoreKeyboardEvents(),"Transfer retained Nifty suppression");}
 }
}
