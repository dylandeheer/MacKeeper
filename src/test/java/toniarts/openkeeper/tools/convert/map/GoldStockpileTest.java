/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.export.*;
import com.jme3.export.binary.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.player.PlayerGoldControl;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.network.game.GameHostedService;
import toniarts.openkeeper.game.state.session.*;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.*;

class GoldStockpileTest {
    @Test void initialFundsAreNotMinedAndRepeatedInitializationDoesNotDuplicateThem() {
        Keeper keeper = new Keeper(false, (short)3);
        PlayerGoldControl gold = new PlayerGoldControl(keeper);
        gold.setInitialGold(5000);
        gold.setInitialGold(5000);
        assertEquals(5000, keeper.getGold());
        assertEquals(5000, keeper.getGoldReserve());
        assertEquals(0, keeper.getGoldMined());
        assertFalse(gold.subGold(1));
        assertFalse(gold.spendGold(5001, 5001));
        assertTrue(gold.spendGold(5000, 5000));
        assertEquals(0, keeper.getGold());
        assertEquals(0, keeper.getGoldReserve());
    }

    @Test void binaryKeeperRoundTripPreservesWideTotalAndSeparateReserve() throws Exception {
        Keeper keeper = new Keeper(false, (short)3);
        keeper.setGold((long) Integer.MAX_VALUE + 600);
        keeper.setGoldReserve(Integer.MAX_VALUE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(keeper, bytes);
        Keeper restored = (Keeper) BinaryImporter.getInstance().load(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(keeper.getGold(), restored.getGold());
        assertEquals(keeper.getGoldReserve(), restored.getGoldReserve());
    }

    @Test void legacyKeeperWithoutReserveReadsOldIntegerBalance() throws Exception {
        InputCapsule capsule = proxy(InputCapsule.class, (name,args) -> {
            if (name.equals("readInt") && args[0].equals("gold")) return 17000;
            return args[args.length - 1];
        });
        Keeper keeper = new Keeper();
        keeper.read(proxy(JmeImporter.class, (name,args) -> capsule));
        assertEquals(17000, keeper.getGold());
        assertEquals(0, keeper.getGoldReserve());
    }

    @Test void oversizedRoomPurchaseIsRejectedEvenWhenWideBalanceCanAffordIt() {
        Room room = new Room();
        room.setFlags(EnumSet.of(Room.RoomFlag.BUILDABLE));
        room.setCost(60000);
        var kwd = proxy(IKwdFile.class, (name,args) -> room);
        var mapData = proxy(toniarts.openkeeper.game.map.IMapDataInformation.class,
                (name,args) -> 200);
        var map = proxy(toniarts.openkeeper.game.map.IMapInformation.class,
                (name,args) -> name.equals("getMapData") ? mapData : true);
        var result = toniarts.openkeeper.game.controller.RoomPlacementValidator.validate(
                kwd, map, Set.of(), new com.jme3.math.Vector2f(0,0),
                new com.jme3.math.Vector2f(199,199), (short)3, (short)1, 2400000000L);
        assertEquals(toniarts.openkeeper.game.controller.RoomPlacementValidator.Failure.TRANSACTION_TOO_LARGE,
                result.failure());
    }

    @Test void localAndHostedCallbacksPreserveWideTotalAndReserve() throws Exception {
        List<List<Object>> received = new ArrayList<>();
        GameSessionListener listener = proxy(GameSessionListener.class, (name,args) -> {
            if (name.equals("onGoldChange")) received.add(List.of(args));
            else if (!name.equals("equals")) fail(name);
            return false;
        });
        long total = (long) Integer.MAX_VALUE + 600;
        LocalGameSession local = new LocalGameSession();
        local.addGameSessionListener(listener);
        local.onGoldChange((short)3, total, Integer.MAX_VALUE);
        GameHostedService host = new GameHostedService();
        connect(host, (short)3, listener);
        host.onGoldChange((short)3, total, Integer.MAX_VALUE);
        assertEquals(List.of(List.of((short)3, total, Integer.MAX_VALUE),
                List.of((short)3, total, Integer.MAX_VALUE)), received);
    }
}
