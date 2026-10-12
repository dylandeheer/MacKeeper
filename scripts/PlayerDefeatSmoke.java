/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.trigger.*;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;

/** Owned-data predicate audit and isolated authored campaign defeat actions, not mission acceptance. */
class PlayerDefeatSmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        int conditions=0; Set<String> maps=new TreeSet<>();
        try(var files=Files.list(Path.of(base,"Data/editor/maps"))) {
            for(Path path:files.filter(p->p.toString().endsWith(".kwd")).sorted().toList()) {
                var kwd=KwdFile.load(base,path);
                for(var raw:kwd.getTriggers().values()) if(raw instanceof TriggerGeneric g&&g.getType()==TriggerGeneric.TargetType.PLAYER_KILLED) {
                    conditions++; maps.add(path.getFileName().toString());
                    short filter=((Number)g.getUserData("playerId")).shortValue();
                    short matching=filter==0?(short)1:filter;
                    for(short killer:new short[]{-1,0,matching, (short)(matching+1)}) {
                        Keeper keeper=new Keeper(false,(short)3);
                        if(killer>=0)keeper.markDestroyed(killer);
                        ILevelInfo info=proxy(ILevelInfo.class,(m,a)->switch(m.getName()) {
                            case "getLevelData"->kwd; case "getPaydayCycle"->0L; case "getPlayer"->keeper;
                            default->throw new AssertionError(m.getName());
                        });
                        var control=new PredicateControl(info,g.getId());
                        require(control.active()==(killer>0&&(filter==0||killer==filter)),path+" trigger="+g.getId()+" killer="+killer);
                    }
                }
            }
        }
        require(conditions==72&&maps.size()==18,"Owned map audit changed: "+conditions+"/"+maps.size());
        System.out.println("PASS all "+conditions+" PLAYER_KILLED predicates in "+maps.size()+" maps, including both authored extra values");
        for(String name:List.of("Level13.kwd","Level16.kwd","Level17.kwd")) exercise(base,name);
    }

    static void exercise(String base,String name)throws Exception {
        var kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps",name));
        var data=new DefaultEntityData(); List<String> callbacks=new ArrayList<>();
        PlayerService service=proxy(PlayerService.class,(m,a)-> {
            if(m.getName().equals("endGame"))callbacks.add(a[1]+":"+a[0]);
            return m.getReturnType()==boolean.class?false:null;
        });
        var game=new GameController(kwd,null,data,kwd.getVariables(),service);
        try {
            if(name.equals("Level17.kwd")) {
                game.getLevelInfo().setFlag(5,0);
                kill(game,data,(short)4,(short)3); runCondition(game,kwd,service,(short)4,57);
                require(game.getLevelInfo().getFlag(5)==1,"First keeper death counter");
                kill(game,data,(short)5,(short)3); runCondition(game,kwd,service,(short)5,58);
                require(game.getLevelInfo().getFlag(5)==2,"Second keeper death counter");
                require(callbacks.isEmpty(),"AI death ended human game");
            } else {
                kill(game,data,(short)3,(short)1);
                require(callbacks.equals(List.of("3:false")),"Direct human defeat");
                var result=game.getGameResult((short)3);
                var time=result.getData(GameResult.ResultType.TIME_TAKEN);
                ((GameTimeController)game.getGameTimer()).processTick(10);
                runCondition(game,kwd,service,(short)3,name.equals("Level13.kwd")?60:52);
                game.endGame((short)3,true); // A later incompatible result must not rewrite the first one.
                require(callbacks.equals(List.of("3:false")),"Script duplicated or replaced result");
                require(game.getGameResult()==result&&result.getData(GameResult.ResultType.TIME_TAKEN).equals(time),"Result/time overwritten");
                require(Boolean.FALSE.equals(result.getData(GameResult.ResultType.LEVEL_WON)),"Loss replaced by win");
                game.endGame((short)4,true); game.endGame((short)4,false);
                require(callbacks.equals(List.of("3:false","4:true")),"Results are not independent per player");
                require(Boolean.TRUE.equals(game.getGameResult((short)4).getData(GameResult.ResultType.LEVEL_WON)),"Other player result");
                require(game.getGameResult((short)5)==null,"Invented unfinished result");
            }
            System.out.println("PASS "+name+" final-heart attribution -> authored defeat actions; terminal callbacks remain per-player and idempotent");
        } finally {game.stop();data.close();}
    }

    static void kill(GameController game,DefaultEntityData data,short victim,short killer) {
        var room=game.getPlayerController(victim).getRoomControl().getDungeonHeart();
        var heart=room.getFloorFurniture().stream().filter(id->data.getComponent(id,ObjectComponent.class).objectId==13).findFirst().orElseThrow();
        Health health=data.getComponent(heart,Health.class);data.setComponent(heart,new Health(0,health.maxHealth));
        require(game.getGameWorldController().destroyDungeonHeart(heart,killer),"Heart transaction failed");
        require(game.getPlayerController(victim).getKeeper().getKilledByPlayerId()==killer,"Attribution missing");
    }

    // Isolate one authored condition and its complete child chain. Sibling mission conditions
    // are outside this test; running them would require a played mission's prerequisite state.
    static void runCondition(GameController game,IKwdFile kwd,PlayerService service,short player,int id) {
        TriggerGeneric raw=(TriggerGeneric)kwd.getTriggers().get(id), isolated=new TriggerGeneric(null);
        isolated.setId(id);isolated.setType(raw.getType());isolated.setRepeatTimes(raw.getRepeatTimes());
        isolated.setTargetValueComparison(raw.getTargetValueComparison());isolated.setIdChild(raw.getIdChild());
        for(String key:raw.getUserDataKeys())isolated.setUserData(key,raw.getUserData(key));
        var triggers=new HashMap<>(kwd.getTriggers());triggers.put(id,isolated);
        IKwdFile view=proxy(IKwdFile.class,(m,a)->m.getName().equals("getTriggers")?triggers:m.invoke(kwd,a));
        ILevelInfo info=proxy(ILevelInfo.class,(m,a)->m.getName().equals("getLevelData")?view:m.invoke(game.getLevelInfo(),a));
        var control=new PlayerTriggerControl(game,info,game.getGameTimer(),game.getGameWorldController().getMapController(),
                game.getGameWorldController().getCreaturesController(),id,player,service);
        for(int i=0;i<30;i++)control.update(.1f);
    }

    static final class PredicateControl extends PlayerTriggerControl {
        PredicateControl(ILevelInfo info,int id){super(null,info,()->0,null,null,id,(short)3,null);}
        boolean active(){return isActive((TriggerGenericData)root.getChild(0));}
    }
    interface Call {Object invoke(Method m,Object[] args)throws Exception;}
    static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m,a)));}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
