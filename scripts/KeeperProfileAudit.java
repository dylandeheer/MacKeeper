/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.tools.convert.map.*;

/** Checks campaign AI identity and authored economy settings without running the simulation. */
class KeeperProfileAudit {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        int maps=0,keepers=0,aiKeepers=0,aiMaps=0;
        try(var files=Files.list(Path.of(base,"Data/editor/maps"))) {
            for(var file:files.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                var level=KwdFile.load(base,file);
                boolean hasAi=false;
                for(var player:level.getPlayers().values()) {
                    if(player.getPlayerId()<Player.KEEPER1_ID)continue;
                    keepers++;
                    var keeper=new Keeper(player);
                    require(keeper.isAi()==player.isAi(),"AI identity lost: "+file);
                    if(!player.isAi())continue;
                    var ai=player.getAiAttributes();
                    require(ai!=null && ai.getAiType()==keeper.getAiType(),"AI profile lost: "+file);
                    require(ai.getDefaultSize()>0 && ai.getBuildOrder()!=null,"Missing economy profile: "+file);
                    for(short id:ai.getBuildOrder())require(id==0||level.getRoomById(id)!=null,"Unknown room in build order: "+file);
                    require(ai.getBuildAreaStartX()<=ai.getBuildAreaEndX() && ai.getBuildAreaStartY()<=ai.getBuildAreaEndY(),"Reversed build area: "+file);
                    aiKeepers++;hasAi=true;
                }
                maps++;if(hasAi)aiMaps++;
            }
        }
        require(maps>0,"No maps found");
        System.out.println("PASS maps="+maps+" keeperSlots="+keepers+" authoredAi="+aiKeepers+" aiMaps="+aiMaps);
        System.out.println("Identity/profile audit only; behavior, preset equivalence and mission completion are not established.");
    }
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
