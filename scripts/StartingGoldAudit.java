/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.tools.convert.map.*;

/** Headless initial-state audit; reads an owned installation and never modifies its assets. */
class StartingGoldAudit {
    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath() + "/";
        int maps = 0, fundedPlayers = 0;
        try (var files = Files.list(Path.of(base, "Data/editor/maps"))) {
            for (var file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                var kwd = KwdFile.load(base, file);
                var data = new DefaultEntityData();
                PlayerService service = (PlayerService) Proxy.newProxyInstance(PlayerService.class.getClassLoader(),
                        new Class<?>[]{PlayerService.class}, (p,m,a) -> m.getReturnType() == boolean.class ? false : null);
                var game = new GameController(kwd, null, data, kwd.getVariables(), service);
                try {
                    for (var player : game.getPlayerControllers().values()) {
                        var keeper = player.getKeeper();
                        int authored = kwd.getPlayer(keeper.getId()).getStartingGold();
                        if (authored > 0) fundedPlayers++;
                        require(keeper.getGoldReserve() == authored, file + " reserve mismatch for " + keeper.getId());
                        require(keeper.getGold() == authored, file + " initial balance mismatch for " + keeper.getId());
                        require(keeper.getGoldMined() == 0, file + " starting funds counted as mined");
                        for (var room : game.getGameWorldController().getMapController().getRoomsByFunction(ObjectType.GOLD, keeper.getId())) {
                            require(room.getObjectControl(ObjectType.GOLD).getCurrentCapacity() == 0,
                                    file + " starting funds occupied room storage");
                        }
                    }
                } finally { game.stop(); data.close(); }
                maps++;
            }
        }
        require(maps > 0, "No maps found");
        System.out.println("PASS maps=" + maps + " fundedPlayers=" + fundedPlayers
                + "; full starting reserves, empty physical storage, zero initial mined gold");
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
