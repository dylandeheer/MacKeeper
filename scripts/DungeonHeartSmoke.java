/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.*;

/** Isolated owned-data combat: real navigation, steering, FSM, authored damage/cooldown/HP and health logic. */
class DungeonHeartSmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        for(String name:List.of("Level1.kwd","TrapTime.kwd","Level17.kwd")) {
            var kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps",name));
            var data=new DefaultEntityData();
            List<Short> defeatCallbacks=new ArrayList<>();
            var service=(PlayerService)Proxy.newProxyInstance(PlayerService.class.getClassLoader(),new Class<?>[]{PlayerService.class},(p,m,a)-> {
                if(m.getName().equals("endGame")) { require(!(boolean)a[0],"Unexpected win callback"); defeatCallbacks.add((short)a[1]); }
                return m.getReturnType()==boolean.class?false:null;
            });
            var game=new GameController(kwd,null,data,kwd.getVariables(),service);
            var world=game.getGameWorldController();
            short owner=name.equals("TrapTime.kwd")?(short)4:(short)3;
            short attacking=owner==3?(short)1:(short)3;
            var losing=game.getPlayerController(owner);
            var winning=game.getPlayerController(attacking);
            var room=losing.getRoomControl().getDungeonHeart();
            require(room!=null,"No test heart");
            EntityId heart=room.getFloorFurniture().stream().filter(id->data.getComponent(id,ObjectComponent.class).objectId==13).findFirst().orElseThrow();
            int initialHealth=data.getComponent(heart,Health.class).health;
            // Remove existing armies to isolate heart mechanics; this is not a mission completion test.
            for(EntityId id:data.findEntities(null,CreatureComponent.class)) data.removeEntity(id);
            var raw=kwd.getCreatureList().stream().filter(c->c.getFightStyle()!=Creature.FightStyle.NON_FIGHTER
                    && !c.getFlags().contains(Creature.CreatureFlag.IS_WORKER) && c.getMeleeDamage()>0)
                    .max(Comparator.comparingInt(Creature::getMeleeDamage)).orElseThrow();
            Point ring=room.getRoomInstance().getCoordinates().stream().filter(p->room.isTileAccessible(null,p)).findFirst().orElseThrow();
            EntityId id=world.getCreaturesController().spawnCreature(raw.getId(),attacking,10,WorldUtils.pointToVector2f(ring),ICreaturesController.SpawnType.PLACE);
            var creature=world.getCreaturesController().createController(id);
            Point center=room.getRoomInstance().getCenter(), start=null;
            var map=world.getMapController().getMapData();
            outer:for(int radius=3;radius<=8;radius++) for(int x=center.x-radius;x<=center.x+radius;x++) for(int y=center.y-radius;y<=center.y+radius;y++) {
                if(x<0||y<0||x>=map.getWidth()||y>=map.getHeight())continue;
                Point candidate=new Point(x,y);
                if(candidate.distance(center)<3 || world.getMapController().getTerrain(map.getTile(candidate)).getFlags().contains(Terrain.TerrainFlag.SOLID))continue;
                if(game.getNavigationService().findPath(candidate,ring,creature)!=null) { start=candidate;break outer; }
            }
            boolean externalApproach=start!=null;
            if(start==null) start=ring; // TrapTime authors an initially sealed heart chamber.
            data.setComponent(id,new Position(0,WorldUtils.pointToVector3f(start)));
            var positions=(PositionSystem)game.getEntityLookupService();positions.processTick(.05f);
            creature.navigateToAttackTarget(heart);
            var route=data.getComponent(id,Navigation.class);
            require(!externalApproach || route!=null&&room.isTileAccessible(null,route.target),"No outer-ring navigation");
            var movement=new MovementSystem(data);
            for(int i=0;i<3600&&data.getComponent(id,Navigation.class)!=null;i++) movement.processTick(1f/60);
            require(data.getComponent(id,Navigation.class)==null,"Movement did not finish");
            positions.processTick(.05f);
            require(creature.getAttackTarget()!=null&&creature.getAttackTarget().getEntityId().equals(heart),"Heart not sensed/selected");
            require(creature.isWithinAttackDistance(heart),"Arrived but cannot attack");
            long initialGold=losing.getKeeper().getGold();
            require(world.addGold(owner,ring,600)==0,"Could not seed physical test treasure");
            int mined=losing.getKeeper().getGoldMined();
            losing.getManaControl().addMana(700);
            if(winning.getManaControl()!=null)winning.getManaControl().addMana(-winning.getKeeper().getMana());
            var health=new HealthSystem(data,positions,kwd.getVariables(),world.getCreaturesController(),game.getLevelInfo(),game.getPlayerControllers().values(),world.getMapController(),world);
            var construction=new DungeonHeartConstruction(data,game.getLevelVariable(Variable.MiscVariable.MiscType.TIME_BEFORE_DUNGEON_HEART_CONSTRUCTION_BEGINS));
            data.setComponent(id,new CreatureAi(0,CreatureState.FIGHT,raw.getId()));
            float dt=.05f;int ticks=0;
            try {
                while(!room.isDestroyed()&&ticks++<12000) {
                    ((GameTimeController)game.getGameTimer()).processTick(dt);
                    positions.processTick(dt);creature.processTick(dt);health.processTick(dt);construction.processTick(dt);
                    for(int i=0;i<3;i++)movement.processTick(1f/60);
                }
                require(room.isDestroyed(),"Heart survived ten simulated minutes");
                require(losing.getKeeper().isDestroyed(),"Keeper not defeated");
                require(losing.getKeeper().getGold()==initialGold,"Physical treasure not debited correctly");
                require(losing.getKeeper().getGoldMined()==mined,"Destruction changed mining metric");
                require(data.findEntities(null,Gold.class,Position.class).stream().mapToInt(e->data.getComponent(e,Gold.class).gold).sum()>=600,"Released treasure missing");
                require(losing.getKeeper().getMana()==0&&winning.getKeeper().getMana()==(winning.getManaControl()==null?0:700),"Mana award mismatch");
                require(defeatCallbacks.equals(losing.getKeeper().isAi()?List.of():List.of(owner)),"Wrong defeat callback");
                require(data.getComponent(room.getEntityId(),ImpGenerator.class)==null,"Destroyed heart still generates imps");
                require(!world.destroyDungeonHeart(heart,attacking),"Repeated destruction accepted");
                // Construction's delayed visual changes must tolerate removed arches and stairs.
                for(int i=0;i<400;i++)construction.processTick(dt);
                System.out.println("PASS "+name+" creature="+raw.getName()+" level=10 HP="+initialHealth+" combatSeconds="+(ticks*dt)
                        +" owner="+owner+" externalApproach="+externalApproach+"; real movement/FSM/cooldown/damage/health, treasure and mana conservation, defeat callback");
            } finally { health.stop();construction.stop();movement.stop();game.stop();data.close(); }
        }
        System.out.println("Isolated sequential combat only; armies removed, no full mission, rendering, network transport or original-engine parity claim.");
    }
    static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
