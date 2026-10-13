/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import com.jme3.input.KeyInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.KeyInputEvent;
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.view.PlayerInteractionState;
/** Actual fresh local-session restoration using privately installed assets. No campaign-completion claim. */
class NativeSessionRestoreSmoke {
 static boolean successful;
 public static void main(String[] args)throws Exception {
  Thread.ofPlatform().daemon().name("restore-watchdog").start(()->{
   try {Thread.sleep(240_000);}catch(InterruptedException e){return;}
   System.err.println("AssertionError: Native session restore timed out");System.exit(1);
  });
  var parse=Main.class.getDeclaredMethod("parseArguments",String[].class);parse.setAccessible(true);parse.invoke(null,(Object)new String[]{"-nomovies"});
  var ctor=Main.class.getDeclaredConstructor();ctor.setAccessible(true);Main app=ctor.newInstance();
  var init=Main.class.getDeclaredMethod("initSettings",Main.class);init.setAccessible(true);init.invoke(null,app);
  app.setShowSettings(false);app.setPauseOnLostFocus(false);app.getStateManager().attach(new Probe());app.start();
  if(!successful)throw new AssertionError("Native session restore did not complete");
 }
 static Object field(Object owner,Class<?> type,String name)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 static GameLoopManager loop(GameServerState server)throws Exception {return (GameLoopManager)field(server,GameServerState.class,"game");}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static class Probe extends AbstractAppState {
  Main app;AppStateManager states;float delay;int stage=-1,cycle;boolean terminal,inputChecked,cancelChecked,cancelDuringLoading;
  LocalSessionArchive saved;IKwdFile definition;GameClientState previous,receiving;Object oldData;PlayerState oldPlayer;
  byte[] expectedServer,profile;double resumedAt;
  final CampaignLevel campaign=new CampaignLevel(CampaignLevel.LevelType.Level,1);
  @Override public void initialize(AppStateManager manager,Application application){super.initialize(manager,application);states=manager;app=(Main)application;}
  @Override public void update(float tpf){try{
   delay+=tpf;
   var client=states.getState(GameClientState.class);var player=states.getState(PlayerState.class);var menu=states.getState(MainMenuState.class);
   if(stage==-1){
    if(menu==null||!menu.isInitialized()||!menu.isEnabled()||app.getNifty().getCurrentScreen()==null
      ||!app.getNifty().getCurrentScreen().getScreenId().equals(MainMenuScreenController.SCREEN_START_ID))return;
    Main.getUserSettings().save();menu.setEnabled(false);LocalGameSession.createLocalGame("Level1",true,states,app,campaign);next(0);return;
   }
   if(stage==0){
    if(client==null||!client.loadingCompletion().toCompletableFuture().isDone()||player==null||!player.isEnabled()||delay<8)return;
    client.loadingCompletion().toCompletableFuture().join();
    definition=client.getLevelData();loop(states.getState(GameServerState.class)).pause();next(1);return;
   }
   if(stage==1){
    if(!player.isPaused()||!client.capturePendingPresentation().events().isEmpty()||delay<.5)return;
    captureAndRetire(client,player);return;
   }
   if(stage==2){
    if(client!=null||states.getState(GameServerState.class)!=null||player!=null||app.getNifty().getScreen("hud")!=null||delay<.3)return;
    if(!cancelChecked){
     var abandoned=LocalGameSession.restoreLocalGame(saved,definition,states,app);var abandonedServer=states.getState(GameServerState.class);
     abandoned.detach();
     check(states.getState(GameClientState.class)==null&&states.getState(GameServerState.class)==null,"Pre-initialize cancellation leaked owners");
     check(abandoned.loadingCompletion().toCompletableFuture().isCompletedExceptionally()&&abandonedServer.loadingCompletion().toCompletableFuture().isCompletedExceptionally(),"Cancellation did not close completion stages");
     check(!app.getNifty().isIgnoreMouseEvents()&&!app.getNifty().isIgnoreKeyboardEvents(),"Cancellation leaked input suppression");
     cancelChecked=true;System.out.println("RESTORE pre-initialize cancellation retires client and prepared server");
    }
    receiving=LocalGameSession.restoreLocalGame(saved,definition,states,app);next(3);inputChecked=false;return;
   }
   if(stage==3){
    var future=receiving.loadingCompletion().toCompletableFuture();
    if(future.isCompletedExceptionally())future.join();
    if(!cancelDuringLoading&&player!=null&&!receiving.isAcceptingInput()){
     receiving.detach();menu.setEnabled(true);cancelDuringLoading=true;
     check(states.getState(GameClientState.class)==null&&states.getState(GameServerState.class)==null,"Loading cancellation leaked session");
     check(!app.getNifty().isIgnoreMouseEvents()&&!app.getNifty().isIgnoreKeyboardEvents(),"Loading cancellation retained input suppression");
     System.out.println("RESTORE map-loading cancellation retires partially constructed views and restores input policy");next(2);return;
    }
    if(!receiving.isAcceptingInput()&&player!=null&&player.isInitialized()&&player.isEnabled()){
     var interaction=states.getState(PlayerInteractionState.class);
     if(interaction!=null&&interaction.isInitialized()){
      var listener=(RawInputListener)field(interaction,PlayerInteractionState.class,"inputListener");
      var route=player.captureScreenState().route();
      int key=(Integer)toniarts.openkeeper.game.data.Settings.Setting.TOGGLE_GUI.getDefaultValue();
      listener.onKeyEvent(new KeyInputEvent(key,' ',true,false));listener.onKeyEvent(new KeyInputEvent(key,' ',false,false));
      check(route.equals(player.captureScreenState().route()),"Loading raw input changed requested screen");
      inputChecked=true;
     }
    }
    if(!future.isDone())return;future.join();
    check(client==receiving&&client!=previous&&player!=oldPlayer&&client.getGameClientService().getEntityData()!=oldData,"Restore reused retired owner");
    check(terminal||inputChecked,"Loading raw input boundary was not exercised");
    check(!app.getNifty().isIgnoreMouseEvents()&&!app.getNifty().isIgnoreKeyboardEvents(),"Restoration retained Nifty input suppression");
    var actual=states.getState(GameServerState.class).captureLocalSession(client);
    if(!Arrays.equals(expectedServer,SimulationArchives.encode(actual.server()))) {
     Files.write(Path.of("work/restore-server-expected.mksa"),expectedServer);Files.write(Path.of("work/restore-server-actual.mksa"),SimulationArchives.encode(actual.server()));
     throw new AssertionError("Paused server changed during fresh client restore");
    }
    check(saved.client().progress().equals(actual.client().progress()),"Playthrough identity/result changed");
    check(saved.client().screen().equals(actual.client().screen()),"Logical screen changed");
    check(Arrays.equals(profile,Files.readAllBytes(toniarts.openkeeper.utils.AppPaths.dataDirectory().resolve("settings.properties"))),"Restore credited profile again");
    check(player.isScreenReady(),"Restored screen unsettled");
    if(!terminal){
     check(actual.client().model().pending().events().isEmpty(),"Pending notifications did not drain");
     check(actual.client().active().messages().messages().size()==saved.client().active().messages().messages().size()+2,"Saved pending messages were lost or replayed");
     check(saved.client().active().interaction().equals(actual.client().active().interaction()),"Tool/enabled flag changed");
     check(saved.client().active().hand().equals(actual.client().active().hand()),"Hand changed");
     check(saved.client().model().keepers().equals(actual.client().model().keepers()),"Observed keeper values changed");
    }
    System.out.println("RESTORE cycle="+cycle+" terminal="+terminal+" independent world, stable paused server, progress, screen, tool, hand, one delivery of pending messages and unchanged profile");
    if(terminal){
     var end=states.getState(EndGameState.class);check(end!=null&&end.isInitialized(),"Missing restored result owner");
     end.onKeyEvent(new KeyInputEvent(KeyInput.KEY_SPACE,' ',false,false));
     check(states.getState(EndGameState.class)==end,"Stale result release escaped boundary");
     end.onKeyEvent(new KeyInputEvent(KeyInput.KEY_SPACE,' ',true,false));end.onKeyEvent(new KeyInputEvent(KeyInput.KEY_SPACE,' ',false,false));
     next(6);return;
    }
    if(++cycle<3){captureAndRetire(client,player);return;}
    resumedAt=loop(states.getState(GameServerState.class)).getGameController().getGameTimer().getGameTime();
    client.getGameClientService().resumeGame();next(4);return;
   }
   if(stage==4){
    if(player.isPaused()||delay<1)return;
    var activeLoop=loop(states.getState(GameServerState.class));
    check(activeLoop.getGameController().getGameTimer().getGameTime()>resumedAt,"Restored simulation did not advance");
    activeLoop.runPlayerCommand(()->activeLoop.getGameController().endGame((short)3,true));activeLoop.pause();next(5);return;
   }
   if(stage==5){
    var end=states.getState(EndGameState.class);
    if(end==null||!end.isInitialized()||!player.isScreenReady()||!client.capturePendingPresentation().events().isEmpty()||delay<.5)return;
    terminal=true;captureAndRetire(client,player);return;
   }
   if(stage==6){
    if(app.getNifty().getCurrentScreen()==null||!app.getNifty().getCurrentScreen().getScreenId().equals("debriefing")||delay<1)return;
    check(menu.getDebriefingResult()!=null&&menu.getDebriefingResult().isWon(),"Restored terminal result lost debriefing");
    check(states.getState(GameClientState.class)==null&&states.getState(GameServerState.class)==null&&states.getState(EndGameState.class)==null,"Terminal teardown leaked session");
    check(Arrays.equals(profile,Files.readAllBytes(toniarts.openkeeper.utils.AppPaths.dataDirectory().resolve("settings.properties"))),"Debriefing replay credited profile");
    System.out.println("RESTORE PASS three active replacements, resumed play, terminal replacement and fresh-input debriefing");successful=true;app.stop();
   }
  }catch(Exception e){throw new RuntimeException(e);}}
  void next(int value){stage=value;delay=0;System.out.println("RESTORE stage="+value);}
  void captureAndRetire(GameClientState client,PlayerState player)throws Exception {
   if(!terminal){
    states.getState(toniarts.openkeeper.view.SystemMessageState.class).setEnabled(true);
    var service=(LocalGameSession)client.getGameClientService();service.showMessage(0,(short)3);service.showMessage(1,(short)3);
   }
   saved=states.getState(GameServerState.class).captureLocalSession(client);saved.validateDefinition(definition);
   Path path=Path.of("work/native-session-restore.mkls");LocalSessionArchives.write(path,saved);saved=LocalSessionArchives.read(path);
   expectedServer=SimulationArchives.encode(saved.server());
   profile=Files.readAllBytes(toniarts.openkeeper.utils.AppPaths.dataDirectory().resolve("settings.properties"));
   try {LocalGameSession.restoreLocalGame(saved,definition,states,app);throw new AssertionError("Active session replacement was accepted");}
   catch(IllegalStateException expected){check(states.getState(GameClientState.class)==client,"Rejected load changed active client");}
   previous=client;oldPlayer=player;oldData=client.getGameClientService().getEntityData();
   player.quitToMainMenu();next(2);
  }
 }
}
