/* SPDX-License-Identifier: GPL-3.0-or-later */
// Reads user-owned data; verifies room transfers, not mission progression.
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.Point;

class RoomOwnershipSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply the original Dungeon Keeper II data directory");
        String base = Path.of(args[0]).toAbsolutePath() + "/";
        var kwd = KwdFile.load(base, Path.of(base,"Data/editor/maps/Level17.kwd"));
        var data = new DefaultEntityData();
        PlayerService service = (PlayerService) Proxy.newProxyInstance(PlayerService.class.getClassLoader(), new Class[]{PlayerService.class},
            (proxy,method,parameters) -> method.getReturnType()==boolean.class ? false : null);
        var game = new GameController(kwd, null, data, kwd.getVariables(), service);
        var map = game.getGameWorldController().getMapController();
        int count=0;
        for (Trigger t : kwd.getTriggers().values()) {
            if (!(t instanceof TriggerAction a) || a.getType()!=TriggerAction.ActionType.CHANGE_ROOM_OWNER) continue;
            int x=t.<Number>getUserData("posX").intValue()-1, y=t.<Number>getUserData("posY").intValue()-1;
            short target=t.<Number>getUserData("playerId").shortValue();
            var p=new Point(x,y);
            var room=map.getRoomControllerByCoordinates(p);
            if(room==null) throw new AssertionError("No room at trigger "+t.getId());
            int before=game.getPlayerControllers().values().stream().mapToInt(c->c.getKeeper().getGold()).sum();
            short previous=room.getOwnerId();
            boolean changed=map.changeRoomOwner(p,target);
            if(room.getOwnerId()!=target || room.getRoomInstance().getOwnerId()!=target) throw new AssertionError("Owner mismatch");
            if(map.changeRoomOwner(p,target)) throw new AssertionError("Non-idempotent transfer");
            for(var tile:room.getRoomInstance().getCoordinates()) if(map.getMapData().getTile(tile).getOwnerId()!=target) throw new AssertionError("Tile mismatch");
            for (var furniture : room.getFloorFurniture()) {
                if (data.getComponent(furniture, Owner.class).ownerId != target) throw new AssertionError("Floor furniture owner mismatch");
            }
            for (var furniture : room.getWallFurniture()) {
                if (data.getComponent(furniture, Owner.class).ownerId != target) throw new AssertionError("Wall furniture owner mismatch");
            }
            int after=game.getPlayerControllers().values().stream().mapToInt(c->c.getKeeper().getGold()).sum();
            if(before!=after) throw new AssertionError("Gold changed "+before+" -> "+after);
            System.out.println("PASS trigger="+t.getId()+" room="+room.getRoom().getName()+" owner="+previous+"->"+target+" changed="+changed);
            count++;
        }
        if(count!=3) throw new AssertionError("Expected all 3 ownership triggers, found "+count);
        System.out.println("PASS all 3 Level17 room transfers in a headless simulation; mission progression not tested");
    }
}
