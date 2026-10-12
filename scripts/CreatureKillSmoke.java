/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.entity.EntityController;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.HealthSystem;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;

/** Owned-map death-to-predicate audit, with representative isolated child actions. */
class CreatureKillSmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath().normalize()+"/";
        int maps=0,conditions=0; Set<String> affected=new TreeSet<>();
        try(var files=Files.list(Path.of(base,"Data/editor/maps"))) {
            for(var file:files.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                IKwdFile source=KwdFile.load(base,file); maps++;
                for(var t:source.getTriggers().values()) if(t instanceof TriggerGeneric g &&
                        (g.getType()==TriggerGeneric.TargetType.PLAYER_CREATURES_KILLED || g.getType()==TriggerGeneric.TargetType.PLAYER_KILLS_CREATURES)) {
                    var owners=source.getPlayers().values().stream().filter(p->contains(source,p.getTriggerId(),g.getId(),new HashSet<>())).toList();
                    require(owners.size()==1,"Unexpected roots "+file+":"+g.getId());
                    exercise(file.getFileName().toString(),source,g,owners.getFirst().getPlayerId());conditions++;affected.add(file.getFileName().toString());
                }
            }
        }
        require(maps==66&&conditions==27&&affected.size()==8,"Changed owned condition inventory");
        System.out.println("PASS 27 death-to-kill predicates in 8 maps, out of 66 parsed; representative speech, flag and party-reinforcement actions");
        System.out.println("Isolated production death/trigger checks; not played missions, native combat presentation, live RMI or complete saves.");
    }
    static void exercise(String name,IKwdFile source,TriggerGeneric raw,short subject)throws Exception {
        boolean losses=raw.getType()==TriggerGeneric.TargetType.PLAYER_CREATURES_KILLED;
        short filter=raw.<Number>getUserData(losses?"killerPlayerId":"victimPlayerId").shortValue();
        short killer=losses?filter:subject, victim=losses?subject:filter;
        require(killer!=0&&victim!=0,"Expected shipped non-wildcard condition");
        int threshold=raw.<Number>getUserData("value").intValue();require(threshold>0&&threshold<=100,"Unbounded fixture");
        TriggerGeneric isolated=new TriggerGeneric(null);isolated.setId(raw.getId());isolated.setType(raw.getType());
        isolated.setTargetValueComparison(raw.getTargetValueComparison());isolated.setRepeatTimes(raw.getRepeatTimes());isolated.setIdChild(raw.getIdChild());
        for(String key:raw.getUserDataKeys())isolated.setUserData(key,raw.getUserData(key));
        var triggers=new HashMap<>(source.getTriggers());triggers.put(raw.getId(),isolated);
        IKwdFile view=proxy(IKwdFile.class,(m,a)->switch(m.getName()) {
            case "getThings"->List.of();case "getTriggers"->triggers;default->m.invoke(source,a);
        });
        Map<Short,Keeper> keepers=new HashMap<>();List<IPlayerController> players=new ArrayList<>();
        for(short id=1;id<=7;id++) {
            Keeper k=new Keeper(false,id);keepers.put(id,k);
            players.add(proxy(IPlayerController.class,(m,a)-> {require(m.getName().equals("getKeeper"),m.getName());return k;}));
        }
        Map<Integer,Integer> flags=new HashMap<>();List<Integer> speech=new ArrayList<>();
        ILevelInfo info=proxy(ILevelInfo.class,(m,a)->switch(m.getName()) {
            case "getLevelData"->view;case "getPaydayCycle"->0L;case "getPlayer"->keepers.get(a[0]);
            case "getFlag"->flags.getOrDefault((int)a[0],0);
            case "setFlag"->{flags.put((int)a[0],(int)a[1]);yield null;}
            default->throw new AssertionError(m.getName());
        });
        PlayerService service=proxy(PlayerService.class,(m,a)-> {
            require(m.getName().equals("playSpeech"),m.getName());require((short)a[4]==subject,"Wrong speech player");speech.add((int)a[0]);return null;
        });
        IMapController map=proxy(IMapController.class,(m,a)-> {require(m.getName().equals("getRoomControllerByCoordinates"),m.getName());return null;});
        DefaultEntityData data=new DefaultEntityData();
        CreaturesController creatures=new CreaturesController(view,data,source.getVariables(),()->0,null,map,info);
        HealthSystem health=new HealthSystem(data,null,source.getVariables(),creatures,info,players,map,null);
        try {
            Creature type=source.getCreatureList().stream().filter(c->c.getFlags().contains(Creature.CreatureFlag.GENERATE_DEAD_BODY)
                    && (victim!=1||c.getFlags().contains(Creature.CreatureFlag.IS_GOOD))).findFirst().orElseThrow();
            Control control=new Control(info,creatures,map,raw.getId(),subject,service);
            // A death credited to the wrong killer must not advance a filtered loss trigger.
            short wrongKiller=(short)(killer==7?6:7);
            kill(data,creatures,health,type.getCreatureId(),victim,wrongKiller);
            require(!control.active(),name+" wrong killer counted");
            for(int count=1;count<=threshold+1;count++) {
                kill(data,creatures,health,type.getCreatureId(),victim,killer);
                boolean expected=raw.getTargetValueComparison()==TriggerGeneric.ComparisonType.EQUAL_TO?count==threshold:count>=threshold;
                require(control.active()==expected,name+":"+raw.getId()+" count="+count);
                if(count==threshold) {
                    boolean actions=(name.equals("Level13.kwd")||name.equals("Level17.kwd")||(name.equals("Level6a.kwd") && (raw.getId()==12 || raw.getId()==13))||(name.equals("Secret2.kwd") && raw.getId()==2));
                    if(actions) {
                        Control actionControl=new Control(info,creatures,map,raw.getId(),subject,service);
                        int before=liveCreatures(data); tick(actionControl);
                        if(name.equals("Level13.kwd"))require(speech.equals(List.of(8)),"Level13 speech");
                        if(name.equals("Level6a.kwd"))require(speech.size()==1,"Level6a speech");
                        if(name.equals("Level17.kwd"))require(flags.values().stream().mapToInt(Integer::intValue).sum()==1,"Level17 flag");
                        if(name.equals("Secret2.kwd"))require(liveCreatures(data)>before,"Secret2 reinforcement creatures");
                        int after=liveCreatures(data), spoken=speech.size();Map<Integer,Integer> oldFlags=new HashMap<>(flags);
                        tick(actionControl);
                        require(liveCreatures(data)==after&&speech.size()==spoken&&flags.equals(oldFlags),"Repeated action");
                    }
                }
            }
            require(keepers.get(victim).getCreaturesLost()==threshold+2,"Loss total lost unknown filtering");
            require(keepers.get(killer).getCreatureKills(victim)==threshold+1,"Kill matrix mismatch");
            System.out.println("PASS "+name+":"+raw.getId()+" subject="+subject+" victim="+victim+" killer="+killer+" threshold="+threshold);
        } finally {health.stop();data.close();}
    }
    static int liveCreatures(DefaultEntityData data) {var entities=data.getEntities(CreatureComponent.class,Health.class);try{return entities.size();}finally{entities.release();}}
    static void kill(DefaultEntityData data,CreaturesController creatures,HealthSystem health,short type,short victim,short killer) {
        EntityId id=creatures.spawnCreature(type,victim,1,new Vector2f(),ICreaturesController.SpawnType.CONJURE);
        CreatureComponent c=new CreatureComponent(data.getComponent(id,CreatureComponent.class));c.diesInstantly=true;data.setComponent(id,c);
        EntityController.setDamage(data,id,data.getComponent(id,Health.class).maxHealth,killer);
        health.processTick(.001f);require(data.getComponent(id,Health.class)==null,"Production death did not run");
    }
    static boolean contains(IKwdFile k,int id,int sought,Set<Integer> seen) {if(id==0||!seen.add(id))return false;if(id==sought)return true;Trigger t=k.getTriggers().get(id);return contains(k,t.getIdChild(),sought,seen)||contains(k,t.getIdNext(),sought,seen);}
    static void tick(PlayerTriggerControl c){for(int i=0;i<30;i++)c.update(.1f);}
    static class Control extends PlayerTriggerControl {
        Control(ILevelInfo info,ICreaturesController c,IMapController map,int id,short owner,PlayerService service){super(null,info,()->0,map,c,id,owner,service);}
        boolean active(){return isActive((TriggerGenericData)root.getChild(0));}
    }
    interface Call{Object invoke(Method m,Object[] a)throws Exception;}
    static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m,a)));}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
