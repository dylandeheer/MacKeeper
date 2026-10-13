/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import com.jme3.input.KeyInput;
import com.jme3.input.event.KeyInputEvent;
import java.nio.file.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
/** Native owned-data flow with an injected terminal result, not a played mission. */
class NativeResultSmoke {
 static boolean win, continuePlaying, successful;
 public static void main(String[] args)throws Exception {
  Thread.ofPlatform().daemon().name("native-result-watchdog").start(()->{
   try {Thread.sleep(180_000);} catch(InterruptedException ignored){return;}
   System.err.println("AssertionError: Native result flow timed out, including a stalled native render loop");
   System.exit(1);
  });
  win=Boolean.parseBoolean(args[0]);continuePlaying=args.length>1&&Boolean.parseBoolean(args[1]);
  var parse=Main.class.getDeclaredMethod("parseArguments",String[].class);parse.setAccessible(true);parse.invoke(null,(Object)new String[]{"-nomovies"});
  var constructor=Main.class.getDeclaredConstructor();constructor.setAccessible(true);Main app=constructor.newInstance();
  var init=Main.class.getDeclaredMethod("initSettings",Main.class);init.setAccessible(true);init.invoke(null,app);
  app.setShowSettings(false);app.setPauseOnLostFocus(false);app.getStateManager().attach(new Probe());app.start();
  if(!successful)throw new AssertionError("Native result flow did not complete");
 }
 static class Probe extends AbstractAppState {
  Main app;AppStateManager states;long began=System.nanoTime();float delay;int stage=-1;double expectedTime;
  final toniarts.openkeeper.game.data.CampaignLevel campaign=new toniarts.openkeeper.game.data.CampaignLevel(toniarts.openkeeper.game.data.CampaignLevel.LevelType.Level,1);
  toniarts.openkeeper.game.state.session.PlayerProgressState progress;int attemptsBefore;boolean rebuiltEnd;toniarts.openkeeper.game.state.session.PlayerScreenState terminalScreen;toniarts.openkeeper.game.state.session.PlayerService service;ScreenshotAppState capture;GameLoopManager cameraLoop;com.jme3.math.Vector3f stationaryPosition;
  @Override public void initialize(AppStateManager states,Application application){super.initialize(states,application);this.states=states;app=(Main)application;capture=new ScreenshotAppState(Path.of("work").toAbsolutePath()+"/","result-flow-"+win+"-"+continuePlaying);capture.setIsNumbered(false);states.attach(capture);}
  @Override public void update(float tpf){try{
   if((System.nanoTime()-began)/1e9>180)throw new AssertionError("Timed out at stage "+stage);
   if(stage==-1){
    var menu=states.getState(MainMenuState.class);
    if(menu==null||!menu.isInitialized()||!menu.isEnabled()||app.getNifty().getCurrentScreen()==null
      ||!app.getNifty().getCurrentScreen().getScreenId().equals(MainMenuScreenController.SCREEN_START_ID))return;
    if((delay+=tpf)<2)return;
    menu.setEnabled(false);
    toniarts.openkeeper.game.state.session.LocalGameSession.createLocalGame("Level1",true,states,app,campaign);
    stage=0;delay=0;
   }
   var player=states.getState(PlayerState.class);
   if(stage==0&&player!=null&&player.isInitialized()&&player.isEnabled()){
    if((delay+=tpf)<4)return;
    progress=player.captureProgressState();attemptsBefore=Main.getUserSettings().getLevelAttempts(campaign);
    if(progress.result()!=null||!progress.campaign().equals(toniarts.openkeeper.game.state.session.PlayerProgressState.Campaign.of(campaign)))throw new AssertionError("Incorrect fresh campaign progress");
    roundTripProgress(progress);
    var server=states.getState(GameServerState.class);var field=GameServerState.class.getDeclaredField("game");field.setAccessible(true);var loop=(GameLoopManager)field.get(server);
    service=(toniarts.openkeeper.game.state.session.PlayerService)states.getState(GameClientState.class).getGameClientService();
    if(continuePlaying){cameraLoop=loop;loop.pause();stage=6;delay=0;return;}
    finishGame(loop);
   }
   if(stage==6&&player.isPaused()){
    service.doTransition((short)250,app.getCamera().getLocation().clone(),(short)3);
    stage=7;delay=0;
   }
   if(stage==7&&(delay+=tpf)>.25f){
    if(service.isInTransition()||!player.isTransitionEnd())throw new AssertionError("Single-frame sweep did not complete");
    System.out.println("FLOW single-frame EnginePath250 completed");
    service.doTransition((short)67,app.getCamera().getLocation().clone(),(short)3);
    stage=8;delay=0;
   }
   if(stage==8){
    delay+=tpf;
    if(delay>.25f&&stationaryPosition==null)stationaryPosition=app.getCamera().getLocation().clone();
    if(delay>.3f&&delay<5&&(!service.isInTransition()||stationaryPosition.distance(app.getCamera().getLocation())>.001f))throw new AssertionError("Stationary path moved or ended early");
    if(delay>6){
     if(service.isInTransition()||!player.isTransitionEnd())throw new AssertionError("Stationary sweep did not complete");
     System.out.println("FLOW stationary EnginePath67 held its position and completed");
     cameraLoop.resume();stage=9;
    }
   }
   if(stage==9&&!player.isPaused()){
    service.doTransition((short)201,app.getCamera().getLocation().clone(),(short)3);
    if(!service.isInTransition())throw new AssertionError("Test transition never started");
    stage=5;delay=0;
   }
   if(stage==5&&(delay+=tpf)>.5f){
    if(!service.isInTransition())throw new AssertionError("Live camera transition did not remain active before result");
    var server=states.getState(GameServerState.class);var field=GameServerState.class.getDeclaredField("game");field.setAccessible(true);
    finishGame((GameLoopManager)field.get(server));
   }
   if(stage==1){var end=states.getState(EndGameState.class);if(end!=null&&end.isInitialized()){
    if((delay+=tpf)<2||!player.isScreenReady())return;
    if(!rebuiltEnd){
     terminalScreen=player.captureScreenState();
     states.detach(end);states.attach(EndGameState.restored(player));rebuiltEnd=true;delay=0;return;
    }
    if(!terminalScreen.equals(player.captureScreenState()))throw new AssertionError("Restored end listener replayed screen actions");
    var joined=states.getState(GameServerState.class).captureLocalSession(states.getState(GameClientState.class));
    joined.validateDefinition(states.getState(GameClientState.class).getLevelData());
    if(joined.client().active()!=null||joined.client().progress().result()==null)throw new AssertionError("Invalid terminal joined owners");
    var joinedBytes=toniarts.openkeeper.game.save.LocalSessionArchives.encode(joined);
    Path joinedPath=Path.of("work/native-terminal-session.mkls");
    for(int i=0;i<3;i++){
     toniarts.openkeeper.game.save.LocalSessionArchives.write(joinedPath,joined);
     joined=toniarts.openkeeper.game.save.LocalSessionArchives.read(joinedPath);
     if(!java.util.Arrays.equals(joinedBytes,toniarts.openkeeper.game.save.LocalSessionArchives.encode(joined)))throw new AssertionError("Terminal joined archive changed");
    }
    System.out.println("FLOW joined terminal server/client save and three exact disk roundtrips without rebuilding inactive HUD owners");
    byte[] profileBefore=Files.readAllBytes(toniarts.openkeeper.utils.AppPaths.dataDirectory().resolve("settings.properties"));
    for(int i=0;i<3;i++){
     Path path=Path.of("work/native-terminal-screen.mkui");
     Files.write(path,toniarts.openkeeper.game.save.PlayerScreenSnapshots.encode(terminalScreen));
     player.restoreScreenState(toniarts.openkeeper.game.save.PlayerScreenSnapshots.decode(Files.readAllBytes(path)));
     if(!terminalScreen.equals(player.captureScreenState()))throw new AssertionError("Terminal screen changed on restore");
    }
    if(!java.util.Arrays.equals(profileBefore,Files.readAllBytes(toniarts.openkeeper.utils.AppPaths.dataDirectory().resolve("settings.properties"))))throw new AssertionError("Screen restore wrote campaign profile");
    int exitKey=continuePlaying?KeyInput.KEY_ESCAPE:KeyInput.KEY_SPACE;
    var stale=new KeyInputEvent(exitKey,' ',false,false);end.onKeyEvent(stale);
    var repeat=new KeyInputEvent(exitKey,' ',true,true);end.onKeyEvent(repeat);end.onKeyEvent(new KeyInputEvent(exitKey,' ',false,false));
    if(!stale.isConsumed()||states.getState(EndGameState.class)!=end||!terminalScreen.equals(player.captureScreenState()))throw new AssertionError("Stale result input escaped boundary");
    end.onKeyEvent(new KeyInputEvent(exitKey,' ',true,false));
    System.out.println("FLOW screen: restored result listener and three disk restores preserve caption/profile; stale releases rejected, fresh key accepted");
    if(continuePlaying&&service.isInTransition())throw new AssertionError("Cancelled transition was not acknowledged");
    var completed=player.captureProgressState();
    if(!completed.attemptId().equals(progress.attemptId())||completed.result()==null)throw new AssertionError("Lost terminal playthrough identity");
    roundTripProgress(completed);
    var settings=Main.getUserSettings();
    if(settings.getLevelAttempts(campaign)!=attemptsBefore+1)throw new AssertionError("Campaign attempt not counted exactly once");
    if(settings.recordCampaignResult(campaign,completed.attemptId(),win)||settings.getLevelAttempts(campaign)!=attemptsBefore+1)throw new AssertionError("Receipt did not suppress repeated outcome");
    var persisted=new com.jme3.system.AppSettings(false);
    try(var in=Files.newInputStream(toniarts.openkeeper.utils.AppPaths.dataDirectory().resolve("settings.properties"))){persisted.load(in);}
    if(!persisted.containsKey("CampaignReceipt."+completed.attemptId()))throw new AssertionError("Receipt missing from disk profile");
    System.out.println("FLOW progress: stable identity, fresh/terminal disk roundtrips, durable receipt, duplicate credit suppressed");
    var key=new KeyInputEvent(continuePlaying?KeyInput.KEY_ESCAPE:KeyInput.KEY_SPACE,' ',false,false);end.onKeyEvent(key);if(!key.isConsumed())throw new AssertionError("End screen key not consumed");
    stage=continuePlaying?4:2;delay=0;System.out.println("FLOW end screen dismissed; continue="+continuePlaying);
   }}
   if(stage==4&&(delay+=tpf)>3){
    if(player==null||!player.isEnabled()||player.isPaused()||states.getState(EndGameState.class)!=null)throw new AssertionError("Continue did not restore one active HUD");
    player.getScreen().pauseMenuNavigate("QUIT","MAIN",null,null);
    var column=app.getNifty().getScreen("hud").findElementById("optionsMenu").findElementById("#columnOne");
    if(column.getChildren().stream().noneMatch(element->element.getElementType().toString().contains("quitToDebriefing")))throw new AssertionError("Continue lost debriefing menu action");
    var panels=player.getScreen().captureHudState();
    var saved=new toniarts.openkeeper.view.HudPanelState(panels.updateRemainder(),panels.cinematicText(),panels.objectiveId(),panels.pendingObjectives(),
      panels.objectiveVisible(),panels.bottomCollapsed(),panels.selectedTab(),new toniarts.openkeeper.view.HudPanelState.Menu(true,
      toniarts.openkeeper.view.HudPanelState.Page.CONFIRMATION,toniarts.openkeeper.view.HudPanelState.Page.QUIT,"Debrief",toniarts.openkeeper.view.HudPanelState.Destination.DEBRIEFING));
    player.getScreen().restoreHudState(saved);
    if(!saved.equals(player.getScreen().captureHudState()))throw new AssertionError("Post-continue debriefing confirmation did not restore");
    player.getScreen().quitToDebriefing();stage=2;delay=0;System.out.println("FLOW continued after outcome and then requested debriefing");
   }
   if(stage==2&&app.getNifty().getCurrentScreen()!=null&&app.getNifty().getCurrentScreen().getScreenId().equals("debriefing")){
    if((delay+=tpf)<3)return;
    var menu=states.getState(MainMenuState.class);var result=menu.getDebriefingResult();
    if(result==null||result.isWon()!=win||result.getElapsedSeconds()!=expectedTime)throw new AssertionError("Lost result during native teardown");
    if(states.getState(EndGameState.class)!=null)throw new AssertionError("Leaked end screen");
    var screen=app.getNifty().getCurrentScreen();
    String shown=screen.findNiftyControl("levelWon",de.lessvoid.nifty.controls.Label.class).getText();
    String shownTime=screen.findNiftyControl("timeTaken",de.lessvoid.nifty.controls.Label.class).getText();
    if(!shown.equals(win?"Yes":"No")||!shownTime.equals(toniarts.openkeeper.utils.Utils.timeToString((int)Math.floor(expectedTime))))throw new AssertionError("Unpopulated rendered labels: "+shown+" / "+shownTime);
    capture.takeScreenshot();stage=3;delay=0;System.out.println("FLOW PASS actual server -> local service -> queued client -> end screen -> teardown -> rendered debriefing");
   }
   if(stage==3&&(delay+=tpf)>2){successful=true;app.stop();}
  }catch(Exception e){throw new RuntimeException(e);}}
  private void roundTripProgress(toniarts.openkeeper.game.state.session.PlayerProgressState expected)throws Exception {
   var saved=expected;
   Path path=Path.of("work/native-player-progress.mkpp");
   for(int i=0;i<3;i++){
    Files.write(path,toniarts.openkeeper.game.save.PlayerProgressSnapshots.encode(saved));
    saved=toniarts.openkeeper.game.save.PlayerProgressSnapshots.decode(Files.readAllBytes(path));
    if(!expected.equals(saved))throw new AssertionError("Changed player progress across disk restore");
   }
  }
  private void finishGame(GameLoopManager loop) {
   loop.getGameController().endGame((short)3,win);expectedTime=loop.getGameController().getGameResult().getElapsedSeconds();
   System.out.println("FLOW server result win="+win+" time="+expectedTime);stage=1;delay=0;
  }
 }
}
