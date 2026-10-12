/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.logic.PaydaySystem;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.task.TaskType;
import toniarts.openkeeper.game.logic.MovementSystem;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.WorldUtils;

/** Owned-data integration: real world, payroll clock, task assignment, destination and collection. */
class PaydaySmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        for(String name:List.of("Level1.kwd","Level2.kwd","Level17.kwd")) {
            IKwdFile kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps",name));
            GameEntityData data=new GameEntityData();
            PlayerService service=(PlayerService)Proxy.newProxyInstance(PlayerService.class.getClassLoader(),new Class<?>[]{PlayerService.class},
                    (p,m,a)->m.getReturnType()==boolean.class?false:null);
            GameController game=new GameController(kwd,null,data,kwd.getVariables(),service);
            var world=game.getGameWorldController();
            short owner=Player.KEEPER1_ID;
            var room=world.getMapController().getRoomsByFunction(ObjectType.GOLD,owner).stream().findFirst().orElseThrow();
            var tile=room.getRoomInstance().getCoordinates().stream().filter(p->room.isTileAccessible(null,p)).findFirst().orElseThrow();
            var raw=kwd.getCreatureList().stream().filter(c->c.getAttributes().getPay()>0&&!c.getFlags().contains(Creature.CreatureFlag.IS_WORKER)).findFirst().orElseThrow();
            var id=world.getCreaturesController().spawnCreature(raw.getId(),owner,1,WorldUtils.pointToVector2f(tile),ICreaturesController.SpawnType.PLACE);
            data.setComponent(id,new Gold(0,raw.getAttributes().getMaxGoldHeld()));
            require(room.getObjectControl(ObjectType.GOLD).getCurrentCapacity()==0,"Initial gold filled storage");
            int reserve=game.getPlayerController(owner).getKeeper().getGoldReserve();
            // Some missions start without funds; seed only the test balance for collection.
            if(reserve<raw.getAttributes().getPay()) {
                game.getPlayerController(owner).getGoldControl().setInitialGold(raw.getAttributes().getPay());
                reserve=raw.getAttributes().getPay();
            }
            double first=kwd.getVariables().get(Variable.MiscVariable.MiscType.TIME_BEFORE_FIRST_PAY_DAY_SECONDS).getValue();
            ((GameTimeController)game.getGameTimer()).processTick((float)first);
            game.processTick(.05f);
            var salary=data.getComponent(id,CreatureSalary.class);
            require(salary!=null&&salary.owed==raw.getAttributes().getPay(),"Wage not charged at authored time");
            verifyRestoredPayday(game, data, kwd);
            var creature=world.getCreaturesController().createController(id);
            Point start = null;
            var map = world.getMapController().getMapData();
            outer: for (int radius=1;radius<10;radius++) for (int x=tile.x-radius;x<=tile.x+radius;x++) for (int y=tile.y-radius;y<=tile.y+radius;y++) {
                if(x<0||y<0||x>=map.getWidth()||y>=map.getHeight())continue;
                Point candidate=new Point(x,y);
                if(world.getMapController().getRoomControllerByCoordinates(candidate)==room)continue;
                if(game.getNavigationService().findPath(candidate,tile,creature)!=null){start=candidate;break outer;}
            }
            require(start!=null,"No external approach tile");
            data.setComponent(id,new Position(0,WorldUtils.pointToVector3f(start)));
            require(creature.collectPay(),"No reachable payday task");
            var task=creature.getAssignedTask();
            require(task.getTaskType()==TaskType.COLLECT_PAY,"Wrong task");
            MovementSystem movement=new MovementSystem(data);
            for(int step=0;step<3600&&data.getComponent(id,Navigation.class)!=null;step++)movement.processTick(1f/60);
            require(data.getComponent(id,Navigation.class)==null,"Navigation did not finish");
            require(creature.isAtAssignedTaskTarget(),"Movement did not reach collection target");
            movement.stop();
            long balance=game.getPlayerController(owner).getKeeper().getGold();
            task.executeTask(creature,1);
            require(data.getComponent(id,CreatureSalary.class).owed==0,"Salary not settled");
            require(data.getComponent(id,Gold.class).gold==raw.getAttributes().getPay(),"Purse mismatch");
            require(balance-game.getPlayerController(owner).getKeeper().getGold()==raw.getAttributes().getPay(),"Keeper debit mismatch");
            require(reserve-game.getPlayerController(owner).getKeeper().getGoldReserve()==raw.getAttributes().getPay(),"Reserve debit mismatch");
            require(room.getObjectControl(ObjectType.GOLD).getCurrentCapacity()==0,"Reserve payment changed physical storage");
            task.executeTask(creature,2);
            require(data.getComponent(id,Gold.class).gold==raw.getAttributes().getPay(),"Repeated task duplicated pay");
            require(game.getLevelInfo().getPaydayCycle()==1,"Cycle not exposed to triggers");
            verifyRestoredPayday(game, data, kwd);
            game.stop();data.close();
            System.out.println("PASS "+name+" first payday="+first+"; real steering/room/task collection and repeated-task conservation; staged payday restore before/after collection");
        }
        System.out.println("Headless integration only; original-engine parity, sound, animation and full missions unverified.");
    }
    static void verifyRestoredPayday(GameController game, GameEntityData data, IKwdFile kwd) throws Exception {
        // This probe is single-threaded, so the game is already at a stable boundary.
        var level = LevelSnapshots.decode(LevelSnapshots.encode(new LevelSnapshots.State(
                LevelState.capture(game.getLevelInfo()), ((GameTimeController) game.getGameTimer()).captureState())));
        byte[] before = EntitySnapshots.encode(data.capture());
        var restoredData = GameEntityData.restore(EntitySnapshots.decode(before));
        var clock = new GameTimeController(level.clock());clock.start();
        var payday = PaydaySystem.restoreState(restoredData, kwd, clock, kwd.getVariables(), new PaydaySystem.State(level.level().paydayCycle()));
        try {
            payday.start();payday.processTick(.05f);
            require(Arrays.equals(before, EntitySnapshots.encode(restoredData.capture())), "Restore charged previous wages or changed ledger/mood");
            require(payday.getCycle() == game.getLevelInfo().getPaydayCycle(), "Payday cycle changed on restore");
        } finally {payday.stop();restoredData.close();}
    }
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
