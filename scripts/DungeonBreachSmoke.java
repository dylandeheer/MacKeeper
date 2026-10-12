/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerLogicController;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.Point;

/** Owned terrain destruction to authored event actions; not played missions. */
class DungeonBreachSmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath().normalize()+"/";
        int maps=0,conditions=0;
        try(var files=Files.list(Path.of(base,"Data/editor/maps"))) {
            for(var file:files.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                var kwd=KwdFile.load(base,file);maps++;
                for(Trigger t:kwd.getTriggers().values()) if(t instanceof TriggerGeneric g &&
                        (g.getType()==TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED||g.getType()==TriggerGeneric.TargetType.PLAYER_ENEMY_BREACHED)) {
                    exercise(file.getFileName().toString(),kwd,g);conditions++;
                }
            }
        }
        require(maps==66&&conditions==2,"Changed owned breach inventory");
        System.out.println("PASS both breach conditions across 66 parsed maps: real terrain destruction, player event routing and authored flag actions.");
        System.out.println("Isolated wall-damage path; not played missions, all original breach causes, rendered digging or live network sessions.");
    }
    static void exercise(String name,IKwdFile kwd,TriggerGeneric event) throws Exception {
        var rootPlayers=kwd.getPlayers().values().stream().filter(p->rootContains(kwd,p.getTriggerId(),event.getId())).toList();
        require(rootPlayers.size()==1,"Event must be a player root");
        short subject=rootPlayers.getFirst().getPlayerId();
        require(subject==3,"Unexpected subject");
        boolean defending=event.getType()==TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED;
        short defender=defending?subject:(short)4,attacker=defending?(short)1:subject;
        int flag=defending?80:0;
        Map<Short,Keeper> keepers=new HashMap<>();kwd.getPlayers().forEach((id,p)->keepers.put(id,new Keeper(p)));
        Map<Integer,Integer> flags=new HashMap<>();int[] writes={0};
        ILevelInfo info=proxy(ILevelInfo.class,(method,a)->switch(method){
            case "getPaydayCycle"->0L;case "getLevelData"->kwd;case "getPlayers"->keepers;case "getPlayer"->keepers.get(a[0]);
            case "getFlag"->flags.getOrDefault(((Number)a[0]).intValue(),0);
            case "setFlag"->{writes[0]++;flags.put(((Number)a[0]).intValue(),(int)a[1]);yield null;}
            default->throw new AssertionError(method);
        });
        var data=new DefaultEntityData();
        try {
            var objects=new ObjectsController(kwd,data,kwd.getVariables(),()->0,null,info);
            var map=new MapController(kwd,objects,kwd.getVariables(),()->0,data,info);
            var logic=new PlayerTriggerLogicController(null,info,()->0,map,null,null);
            Point location=null;
            for(int y=0;y<map.getMapData().getHeight()&&location==null;y++)for(int x=0;x<map.getMapData().getWidth();x++) {
                var tile=map.getMapData().getTile(x,y);var terrain=kwd.getTerrain(tile.getTerrainId());
                if(tile.getOwnerId()==defender&&terrain.getFlags().containsAll(EnumSet.of(Terrain.TerrainFlag.SOLID,Terrain.TerrainFlag.OWNABLE))) {location=new Point(x,y);break;}
            }
            require(location!=null,"No authored owned wall for "+name+" defender="+defender);
            var tile=map.getMapData().getTile(location);short originalTerrain=tile.getTerrainId();
            int startingHealth=tile.getHealth(),hits=0;
            logic.start();
            try {
                while(tile.getTerrainId()==originalTerrain&&hits++<10000) {
                    map.damageTile(location,attacker,null);
                    if(tile.getTerrainId()==originalTerrain)require(flags.isEmpty(),"Premature breach on partial damage");
                }
                require(tile.getTerrainId()!=originalTerrain,"Wall did not break");
                require(flags.equals(Map.of(flag,1))&&writes[0]==1,"Wrong event action "+flags+" writes="+writes[0]);
                map.alterTerrain(location,originalTerrain,defender);
                for(int i=0;i<hits;i++)map.damageTile(location,attacker,null);
                require(writes[0]==1,"One-shot event repeated");
                System.out.println("PASS "+name+":"+event.getId()+" player="+subject+" wall="+location+" HP="+startingHealth+" hits="+hits+" flag="+flag);
            } finally {logic.stop();}
        } finally {data.close();}
    }
    static boolean rootContains(IKwdFile k,int id,int sought) {Set<Integer> seen=new HashSet<>();while(id!=0&&seen.add(id)){if(id==sought)return true;id=k.getTriggers().get(id).getIdNext();}return false;}
    interface Call{Object invoke(String name,Object[] args)throws Exception;}
    static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
