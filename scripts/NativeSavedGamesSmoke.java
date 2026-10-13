/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import de.lessvoid.nifty.controls.*;
import de.lessvoid.nifty.elements.Element;
import de.lessvoid.nifty.elements.render.TextRenderer;
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.utils.AppPaths;

/** Exercises real Nifty menu actions and the complete save browser against owned Level1 assets. */
class NativeSavedGamesSmoke {
 static boolean successful;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(240_000);}catch(InterruptedException e){return;}System.err.println("AssertionError: Save browser timed out");System.exit(1);});
  var parse=Main.class.getDeclaredMethod("parseArguments",String[].class);parse.setAccessible(true);parse.invoke(null,(Object)new String[]{"-nomovies"});
  var ctor=Main.class.getDeclaredConstructor();ctor.setAccessible(true);Main app=ctor.newInstance();
  var init=Main.class.getDeclaredMethod("initSettings",Main.class);init.setAccessible(true);init.invoke(null,app);
  app.setShowSettings(false);app.setPauseOnLostFocus(false);app.getStateManager().attach(new Probe());app.start();
  if(!successful)throw new AssertionError("Native save browser did not complete");
 }
 static class Probe extends AbstractAppState {
  Main app;AppStateManager states;int stage;float delay;GameClientState original;byte[] before;LocalSessionArchive saved;
  final Path file=AppPaths.dataDirectory().resolve("Saves/Browser check.mkls");
  @Override public void initialize(AppStateManager manager,Application application){super.initialize(manager,application);
            application.getListener().setVolume(0); // Keep native playback running without audible test output.
states=manager;app=(Main)application;}
  void next(int value){stage=value;delay=0;System.out.println("BROWSER stage="+value);}
  Element popup(){return app.getNifty().getTopMostPopup();}
  String status(){return popup().findElementById("saveStatus").getRenderer(TextRenderer.class).getOriginalText();}
  boolean ready(){return popup()!=null&&popup().findElementById("saveRefresh")!=null&&popup().findNiftyControl("saveRefresh",Button.class).isEnabled()&&delay>.3;}
  void click(String id){popup().findElementById(id).onClick();}
  ListBox<SaveFiles.Entry> list(){return popup().findNiftyControl("saveList",ListBox.class);}
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
    if(!player.isPaused()||delay<.5)return;
    ((PlayerScreenController)app.getNifty().getScreen("hud").getScreenController()).saveGame();next(3);return;
   }
   if(stage==3){
    if(!ready())return;check(!client.isAcceptingInput(),"Popup leaked raw gameplay input");
    popup().findNiftyControl("saveName",TextField.class).setText("Browser check");click("saveWrite");next(4);return;
   }
   if(stage==4){
    if(!ready())return;check(status().contains("Saved"),"Save did not succeed: "+status());
    saved=LocalSessionArchives.read(file);before=Files.readAllBytes(file);check(list().itemCount()==1,"Saved row missing");
    var shot=states.getState(ScreenshotAppState.class);shot.setFilePath(Path.of("work").toAbsolutePath()+"/");shot.setFileName("native-save-browser");shot.setIsNumbered(false);shot.takeScreenshot();
    click("saveWrite");next(5);return;
   }
   if(stage==5){
    if(!ready())return;check(status().contains("exists"),"Overwrite needs explicit confirmation: "+status());
    check(Arrays.equals(before,Files.readAllBytes(file)),"First overwrite click changed archive");check(popup().findNiftyControl("saveWrite",Button.class).getText().equals("Replace save"),"Confirmation button absent");
    click("saveWrite");next(6);return;
   }
   if(stage==6){
    if(!ready())return;check(status().contains("Saved"),"Confirmed overwrite failed: "+status());LocalSessionArchives.read(file);
    original=client;click("saveLoad");next(7);return;
   }
   if(stage==7){
    if(client==original||client==null||!client.loadingCompletion().toCompletableFuture().isDone()||states.getState(LocalSessionLoader.class)!=null||delay<1)return;
    check(client.isAcceptingInput()&&states.getState(SavedGamesState.class)==null,"Loaded game retained popup input block");
    check(new SaveFiles(AppPaths.dataDirectory()).list(true).size()==1,"Active loading lost recovery checkpoint");
    original=client;Files.write(AppPaths.dataDirectory().resolve("Saves/Broken.mkls"),new byte[]{1,2,3});
    ((PlayerScreenController)app.getNifty().getScreen("hud").getScreenController()).loadGame();next(8);return;
   }
   if(stage==8){
    if(!ready())return;var broken=list().getItems().stream().filter(e->e.name().equals("Broken")).findFirst().orElseThrow();list().selectItem(broken);click("saveLoad");next(9);return;
   }
   if(stage==9){
    if(!ready())return;check(status().contains("Could not load"),"Corrupt archive error absent: "+status());check(client==original&&!client.isAcceptingInput(),"Failed load lost old owner or modal hold");
    var c=saved.client();var sound=c.sound();var p=sound.background();check(p!=null,"Missing actual music fixture");
    var wrong=new toniarts.openkeeper.game.sound.SoundSnapshot.Playback(p.file(),"0".repeat(64),p.seconds(),p.status(),p.volume());
    var corrupt=new toniarts.openkeeper.game.sound.SoundSnapshot(sound.playerId(),sound.levelCategory(),sound.enabled(),sound.backgroundCategory(),sound.playlist(),sound.nextTrack(),sound.pending(),sound.speech(),wrong);
    LocalSessionArchives.write(AppPaths.dataDirectory().resolve("Saves/Broken.mkls"),new LocalSessionArchive(saved.campaign(),saved.server(),new ClientState(c.model(),c.progress(),c.screen(),c.fog(),corrupt,c.worldView(),c.active())));
    click("saveLoad");next(90);return;
   }
   if(stage==90){
    if(client==original||client==null||!ready()||states.getState(LocalSessionLoader.class)!=null)return;
    check(status().contains("previous game has been restored"),"Activation rollback feedback absent: "+status());
    check(!client.isAcceptingInput(),"Reopened error browser did not hold input");original=client;
    click("saveRecovery");next(10);return;
   }
   if(stage==10){
    if(!ready())return;check(list().itemCount()==2&&list().getItems().getFirst().recovery(),"Recovery browser list incorrect");
    click("saveLoad");next(100);return;
   }
   if(stage==100){
    if(client==original||client==null||!client.isAcceptingInput()||states.getState(LocalSessionLoader.class)!=null||states.getState(SavedGamesState.class)!=null||delay<.5)return;
    ((PlayerScreenController)app.getNifty().getScreen("hud").getScreenController()).loadGame();next(101);return;
   }
   if(stage==101){
    if(!ready())return;
    click("saveBack");next(11);return;
   }
   if(stage==11){
    if(states.getState(SavedGamesState.class)!=null||delay<.4)return;check(client.isAcceptingInput()&&player.isPaused(),"Back did not restore paused input state");
    player.setPaused(false);next(12);return;
   }
   if(stage==12){
    if(player.isPaused()||delay<.3)return;SavedGamesState.open(app,false);next(13);return;
   }
   if(stage==13){
    if(!ready()||!player.isPaused())return;popup().findNiftyControl("saveBack",Button.class).setFocus();
    ((com.jme3.niftygui.NiftyJmeDisplay)app.getGuiViewPort().getProcessors().stream().filter(p -> p instanceof com.jme3.niftygui.NiftyJmeDisplay).findFirst().orElseThrow()).simulateKeyEvent(new com.jme3.input.event.KeyInputEvent(com.jme3.input.KeyInput.KEY_ESCAPE,'\u001b',true,false));next(130);return;
   }
   if(stage==130){
    if(delay<.3)return;check(states.getState(SavedGamesState.class)!=null&&player.isPaused(),"Escape press closed dialog or resumed underlying game");
    ((com.jme3.niftygui.NiftyJmeDisplay)app.getGuiViewPort().getProcessors().stream().filter(p -> p instanceof com.jme3.niftygui.NiftyJmeDisplay).findFirst().orElseThrow()).simulateKeyEvent(new com.jme3.input.event.KeyInputEvent(com.jme3.input.KeyInput.KEY_ESCAPE,'\u001b',false,false));next(14);return;
   }
   if(stage==14){
    if(states.getState(SavedGamesState.class)!=null||player.isPaused()||delay<.3)return;check(client.isAcceptingInput(),"Escape retained modal input hold");
    player.quitToMainMenu();next(15);return;
   }
   if(stage==15){
    if(client!=null||player!=null||app.getNifty().getScreen("hud")!=null||menu==null||!menu.isEnabled()||delay<1)return;
    ((MainMenuScreenController)app.getNifty().getCurrentScreen().getScreenController()).loadGame();next(16);return;
   }
   if(stage==16){
    if(!ready())return;check(!popup().findElementById("saveWrite").isVisible(),"Menu browser allows saving without game");
    var good=list().getItems().stream().filter(e->e.name().equals("Browser check")).findFirst().orElseThrow();list().selectItem(good);click("saveLoad");next(17);return;
   }
   if(stage==17){
    if(client==null||!client.loadingCompletion().toCompletableFuture().isDone()||states.getState(LocalSessionLoader.class)!=null||delay<1)return;
    client.loadingCompletion().toCompletableFuture().join();check(client.isAcceptingInput()&&states.getState(SavedGamesState.class)==null,"Menu load did not settle");
    System.out.println("BROWSER PASS menu actions, save, overwrite confirmation, active load, corrupt-file feedback, activation rollback feedback, recovery loading, back, Escape/resume and menu load");successful=true;app.stop();
   }
  }catch(Throwable error){error.printStackTrace();app.stop();}}
 }
}
