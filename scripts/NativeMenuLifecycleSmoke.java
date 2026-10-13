/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import com.jme3.asset.AssetManager;
import java.lang.reflect.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.view.PlayerEntityViewState;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.loading.LoadingState;
import toniarts.openkeeper.game.state.loading.SingleBarLoadingState;
/** Hold actual asset loading across disable/cleanup to exercise the asynchronous menu handoff. */
class NativeMenuLifecycleSmoke {
 static boolean successful;
 static boolean retire;
 static Object field(Object owner,Class<?> type,String name)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  retire=args.length>0&&args[0].equals("retire");
  Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(120_000);}catch(InterruptedException e){return;}System.err.println("AssertionError: Menu lifecycle timed out");System.exit(1);});
  var parse=Main.class.getDeclaredMethod("parseArguments",String[].class);parse.setAccessible(true);parse.invoke(null,(Object)new String[]{"-nomovies","-level","Level1"});
  var ctor=Main.class.getDeclaredConstructor();ctor.setAccessible(true);Main app=ctor.newInstance();
  var init=Main.class.getDeclaredMethod("initSettings",Main.class);init.setAccessible(true);init.invoke(null,app);
  app.setShowSettings(false);app.setPauseOnLostFocus(false);app.getStateManager().attach(new Probe());app.start();
  if(!successful)throw new AssertionError("Native menu lifecycle did not complete");
 }
 static class Probe extends AbstractAppState {
  Main app;AppStateManager states;MainMenuState menu;Thread worker;int stage;float delay;
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  final AtomicBoolean gate=new AtomicBoolean();
  @Override public void initialize(AppStateManager manager,Application application){super.initialize(manager,application);states=manager;app=(Main)application;}
  @Override public void update(float tpf){try{
   delay+=tpf;
   if(stage==0){
    var client=states.getState(GameClientState.class);
    if(client==null||!client.loadingCompletion().toCompletableFuture().isDone())return;
    client.loadingCompletion().toCompletableFuture().join();
    menu=states.getState(MainMenuState.class);check(field(menu,MainMenuState.class,"menuNode")==null,"Direct startup unexpectedly loaded menu");
    AssetManager actual=app.getAssetManager();
    AssetManager delayed=(AssetManager)Proxy.newProxyInstance(AssetManager.class.getClassLoader(),new Class[]{AssetManager.class},(proxy,method,args)->{
     if(gate.compareAndSet(false,true)){
      entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
     }
     try{return method.invoke(actual,args);}catch(InvocationTargetException e){throw e.getCause();}
    });
    var assetField=MainMenuState.class.getDeclaredField("assetManager");assetField.setAccessible(true);assetField.set(menu,delayed);
    states.getState(PlayerState.class).quitToMainMenu();stage=1;delay=0;return;
   }
   if(stage==1){
    if(entered.getCount()!=0)return;
    var loader=(SingleBarLoadingState)field(menu,MainMenuState.class,"menuLoader");check(loader!=null,"No pending loader");
    worker=(Thread)field(loader,LoadingState.class,"loadingThread");
    menu.setEnabled(false);menu.setEnabled(true);
    check(field(menu,MainMenuState.class,"menuLoader")==loader,"Repeated enable launched another loader");
    if(retire){
     states.detach(menu);
     Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(250);}catch(InterruptedException ignored){}release.countDown();});
    }else{menu.setEnabled(false);release.countDown();}
    stage=2;delay=0;return;
   }
   if(stage==2){
    if(worker.isAlive()||delay<1)return;
    check(field(menu,MainMenuState.class,"menuLoader")==null,"Completed loader retained");
    var view=states.getState("MainMenu: 3",PlayerEntityViewState.class);
    if(retire){
     check(states.getState(MainMenuState.class)==null&&view==null,"Retired menu published an owner");
     check(field(menu,MainMenuState.class,"gameController")==null&&field(menu,MainMenuState.class,"menuNode")==null,"Retired menu retained scene resources");
     check(states.getState(SingleBarLoadingState.class)==null,"Retired menu retained loading screen");
     System.out.println("MENU retired during blocked asset loading: no late scene, controller, view, or loading screen");finish();return;
    }
    check(view!=null&&!view.isEnabled(),"Disabled menu completion activated entity view");
    var node=(com.jme3.scene.Node)field(menu,MainMenuState.class,"menuNode");check(node!=null&&node.getParent()==null,"Disabled menu completion attached root");
    menu.setEnabled(true);stage=3;delay=0;return;
   }
   if(stage==3&&delay>1){
    check(app.getNifty().getCurrentScreen().getScreenId().equals(MainMenuScreenController.SCREEN_START_ID),"Re-enabled menu did not show start screen");
    check(states.getState("MainMenu: 3",PlayerEntityViewState.class).isEnabled(),"Re-enabled view inactive");
    System.out.println("MENU disabled during blocked loading: one loader, quiet completion, successful later activation");finish();
   }
  }catch(Throwable failure){release.countDown();failure.printStackTrace();app.stop();}}
  void finish(){successful=true;app.stop();}
 }
}
