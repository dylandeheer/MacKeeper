/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.controller.player;

import com.jme3.util.SafeArrayList;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.listener.RoomListener;
import toniarts.openkeeper.game.listener.PlayerGoldListener;

/**
 *
 * @author ArchDemon
 */
public final class PlayerGoldControl implements RoomListener {

    private int goldMax = 0;
    private final Keeper keeper;
    private final SafeArrayList<PlayerGoldListener> listeners = new SafeArrayList<>(PlayerGoldListener.class);

    public PlayerGoldControl(Keeper keeper) {
        this.keeper = keeper;
    }

    public void addGold(int value) {
        if (value <= 0) {
            return;
        }

        keeper.setGold(Math.addExact(keeper.getGold(), value));
        keeper.setGoldMined(keeper.getGoldMined() + value); // Nope, need to get this some other way
        updateListeners();
    }

    /** Sets the initial reserve without consuming room capacity or counting it as mined gold. */
    public void setInitialGold(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative initial gold");
        long total = Math.addExact(keeper.getGold() - keeper.getGoldReserve(), value);
        keeper.setGoldReserve(value);
        keeper.setGold(total);
        updateListeners();
    }

    /** Debit physically removed gold; pickup/destruction must not consume the reserve. */
    public boolean subGold(int value) { return spendGold(value, 0); }

    /** Record a completed withdrawal, explicitly identifying its reserve portion. */
    public boolean spendGold(int value, int reserveSpent) {
        if (value > 0 && reserveSpent >= 0 && reserveSpent <= value
                && keeper.getGoldReserve() >= reserveSpent
                && keeper.getGold() - keeper.getGoldReserve() >= value - reserveSpent) {
            keeper.setGoldReserve(keeper.getGoldReserve() - reserveSpent);
            keeper.setGold(keeper.getGold() - value);
            updateListeners();
            return true;
        }
        return false;
    }

    @Override
    public void onCaptured(IRoomController room) {
        transferRoomStorage(room, 1);
    }

    @Override
    public void onCapturedByEnemy(IRoomController room) {
        transferRoomStorage(room, -1);
    }

    private void transferRoomStorage(IRoomController room, int direction) {
        if (!room.canStoreGold()) return;
        var storage = room.getObjectControl(ObjectType.GOLD);
        goldMax += direction * storage.getMaxCapacity();
        keeper.setGold(keeper.getGold() + direction * storage.getCurrentCapacity());
        // Captured treasure changes spendable gold, not the amount mined.
        updateListeners();
    }

    // Build/sale capacity accounting is performed by GameWorldController.
    @Override public void onBuild(IRoomController room) { }
    @Override public void onSold(IRoomController room) { }

    public void addListener(PlayerGoldListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PlayerGoldListener listener) {
        listeners.remove(listener);
    }

    private void updateListeners() {
        for (PlayerGoldListener listener : listeners.getArray()) {
            listener.onGoldChange(keeper.getId(), keeper.getGold(), keeper.getGoldReserve());
        }
    }

    public void setGoldMax(int goldMax) {
        this.goldMax = goldMax;
    }

    public int getGoldMax() {
        return goldMax;
    }

    public boolean isFullCapacity() {
        return keeper.getGold() - keeper.getGoldReserve() >= goldMax;
    }
}
