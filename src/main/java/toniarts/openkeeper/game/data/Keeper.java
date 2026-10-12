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
package toniarts.openkeeper.game.data;

import com.jme3.export.InputCapsule;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.OutputCapsule;
import com.jme3.export.Savable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import toniarts.openkeeper.tools.convert.map.AI.AIType;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.utils.Point;

/**
 * Your FRIENDLY neighborhood Keeper, or not
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class Keeper implements Comparable<Keeper>, IIndexable, Savable {

    private boolean ai;
    private AIType aiType = AIType.MASTER_KEEPER;

    private long gold;
    private int goldReserve;
    private int goldMined;
    private int mana;
    private int manaGain;
    private int manaLoose;
    private int maxMana;
    private Point dungeonHeartLocation;
    private boolean possession;
    private List<ResearchableEntity> availableRooms = new ArrayList<>();
    private List<ResearchableEntity> availableSpells = new ArrayList<>();
    private List<Short> availableCreatures = new ArrayList<>();
    private List<ResearchableEntity> availableDoors = new ArrayList<>();
    private List<ResearchableEntity> availableTraps = new ArrayList<>();
    private ResearchableEntity currentResearch;

    private transient Player player;
    private short id;
    private boolean destroyed = false;
    private short killedByPlayerId;
    private long creaturesLost;
    private long enemyCreaturesKilled;
    private long heroesKilled;
    private long[] creatureKillsByPlayer = new long[256];
    private Set<Short> allies = HashSet.newHashSet(4);

    public Keeper() {

    }

    public Keeper(boolean ai, short id) {
        this.ai = ai;
        this.id = id;
    }

    public Keeper(Player player) {
        this.player = player;
        this.id = player.getPlayerId();
        this.ai = player.isAi();
        if (player.getAiAttributes() != null && player.getAiAttributes().getAiType() != null) {
            this.aiType = player.getAiAttributes().getAiType();
        }
    }

    @Override
    public short getId() {
        return id;
    }

    public void setId(short id) {
        this.id = id;
    }

    public Player getPlayer() {
        return player;
    }

    public int getGoldMined() {
        return goldMined;
    }

    public void setGoldMined(int goldMined) {
        this.goldMined = goldMined;
    }

    public void setManaGain(int manaGain) {
        this.manaGain = manaGain;
    }

    public int getManaGain() {
        return manaGain;
    }

    public void setManaLoose(int manaLoose) {
        this.manaLoose = manaLoose;
    }

    public int getManaLoose() {
        return manaLoose;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    @Override
    public String toString() {
        return Short.toString(id);
    }

    /**
     * Check whether the player is destroyed or not
     *
     * @return is destroyed
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    /** Record the first final-heart defeat. Zero means the killer is unknown. */
    public void markDestroyed(short killerId) {
        if (destroyed) return;
        killedByPlayerId = killerId;
        destroyed = true;
    }

    public short getKilledByPlayerId() { return killedByPlayerId; }

    public long getCreaturesLost() { return creaturesLost; }

    /** Native wildcard kill queries count non-hero enemies; filtered queries count every species. */
    public long getCreatureKills(short victimPlayerId) {
        return victimPlayerId == 0 ? enemyCreaturesKilled : creatureKillsByPlayer[victimPlayerId];
    }

    public long getHeroesKilled() { return heroesKilled; }

    public void recordCreatureLoss() { creaturesLost++; }

    public void recordCreatureKill(short victimPlayerId, boolean hero) {
        if (victimPlayerId < 0 || victimPlayerId >= creatureKillsByPlayer.length) {
            throw new IllegalArgumentException("Invalid victim player ID: " + victimPlayerId);
        }
        creatureKillsByPlayer[victimPlayerId]++;
        if (hero) heroesKilled++;
        else if (victimPlayerId != id) enemyCreaturesKilled++;
    }

    public AIType getAiType() {
        return aiType;
    }

    public void setAiType(AIType aiType) {
        this.aiType = aiType;
    }

    public boolean isAi() {
        return ai;
    }

    /**
     * Is the given player an enemy of ours
     *
     * @param playerId the other player
     * @return is the player enemy of ours
     * @see #isAlly(short)
     */
    public boolean isEnemy(short playerId) {
        if (playerId == id || playerId == Player.NEUTRAL_PLAYER_ID || id == Player.NEUTRAL_PLAYER_ID) {
            return false; // Neutral player has no enemies
        }
        return !allies.contains(playerId);
    }

    /**
     * Is the player an ally of us. If not, it is not necessarily the enemy. But
     * we do not share vision, battles etc. with non-allies
     *
     * @param playerId the other player
     * @return is the player an ally of ours
     * @see #isEnemy(short)
     */
    public boolean isAlly(short playerId) {
        return id == playerId || allies.contains(playerId);
    }

    /**
     * Create alliance between two players, remember to set both players as
     * allies
     *
     * @param playerId the player to form alliance with
     */
    public void createAlliance(short playerId) {
        allies.add(playerId);
    }

    /**
     * Breaks alliance between two players, remember to break the alliance on
     * both players
     *
     * @param playerId the player to break alliance with
     */
    public void breakAlliance(short playerId) {
        allies.remove(playerId);
    }

    /** Unspent initial stockpile; included in total gold, outside physical room storage. */
    public int getGoldReserve() { return goldReserve; }

    public void setGoldReserve(int goldReserve) {
        if (goldReserve < 0) throw new IllegalArgumentException("Negative gold reserve");
        this.goldReserve = goldReserve;
    }

    public long getGold() {
        return gold;
    }

    public int getMana() {
        return mana;
    }

    public void setGold(long gold) {
        this.gold = gold;
    }

    public void setMana(int mana) {
        this.mana = mana;
    }

    public int getMaxMana() {
        return maxMana;
    }

    public void setMaxMana(int maxMana) {
        this.maxMana = maxMana;
    }

    public Point getDungeonHeartLocation() {
        return dungeonHeartLocation;
    }

    public void setDungeonHeartLocation(Point dungeonHeartLocation) {
        this.dungeonHeartLocation = dungeonHeartLocation;
    }

    public boolean isPossession() {
        return possession;
    }

    public void setPossession(boolean possession) {
        this.possession = possession;
    }

    public List<ResearchableEntity> getAvailableRooms() {
        return availableRooms;
    }

    public List<ResearchableEntity> getAvailableSpells() {
        return availableSpells;
    }

    public List<Short> getAvailableCreatures() {
        return availableCreatures;
    }

    public List<ResearchableEntity> getAvailableDoors() {
        return availableDoors;
    }

    public List<ResearchableEntity> getAvailableTraps() {
        return availableTraps;
    }

    public void setCurrentResearch(ResearchableEntity currentResearch) {
        this.currentResearch = currentResearch;
    }

    public ResearchableEntity getCurrentResearch() {
        return currentResearch;
    }

    /** Copy under the simulation snapshot boundary. Map definitions are not copied. */
    public KeeperState captureState() {
        boolean shared = currentResearch != null && java.util.stream.Stream.of(
                availableRooms, availableSpells, availableDoors, availableTraps)
                .flatMap(Collection::stream).anyMatch(entity -> entity == currentResearch);
        return new KeeperState(id, ai, aiType, gold, goldReserve, goldMined, mana, manaGain, manaLoose, maxMana,
                dungeonHeartLocation, possession,
                availableRooms.stream().map(KeeperState.Research::capture).toList(),
                availableSpells.stream().map(KeeperState.Research::capture).toList(), availableCreatures,
                availableDoors.stream().map(KeeperState.Research::capture).toList(),
                availableTraps.stream().map(KeeperState.Research::capture).toList(),
                currentResearch == null ? null : KeeperState.Research.capture(currentResearch), shared,
                destroyed, killedByPlayerId, creaturesLost, enemyCreaturesKilled, heroesKilled,
                java.util.Arrays.stream(creatureKillsByPlayer).boxed().toList(), allies);
    }

    /** Reconstruct values without initial money, research initialization or defeat callbacks. */
    public static Keeper restoreState(KeeperState state, Player definition) {
        if (definition != null && definition.getPlayerId() != state.id()) {
            throw new IllegalArgumentException("Keeper does not match map player definition");
        }
        Keeper result = new Keeper(state.ai(), state.id());
        result.player = definition;result.aiType = state.aiType();
        result.gold = state.gold();result.goldReserve = state.goldReserve();result.goldMined = state.goldMined();
        result.mana = state.mana();result.manaGain = state.manaGain();result.manaLoose = state.manaLoose();result.maxMana = state.maxMana();
        result.dungeonHeartLocation = state.dungeonHeartLocation();result.possession = state.possession();
        result.availableRooms = restoreResearch(state.availableRooms());result.availableSpells = restoreResearch(state.availableSpells());
        result.availableDoors = restoreResearch(state.availableDoors());result.availableTraps = restoreResearch(state.availableTraps());
        result.availableCreatures = new ArrayList<>(state.availableCreatures());
        if (state.currentResearch() != null) {
            if (state.currentResearchShared()) {
                result.currentResearch = java.util.stream.Stream.of(result.availableRooms, result.availableSpells,
                        result.availableDoors, result.availableTraps).flatMap(Collection::stream)
                        .filter(entity -> entity.getId() == state.currentResearch().id()
                                && entity.getResearchableType() == state.currentResearch().type()).findFirst().orElseThrow();
            } else result.currentResearch = state.currentResearch().restore();
        }
        result.destroyed = state.destroyed();result.killedByPlayerId = state.killedByPlayerId();
        result.creaturesLost = state.creaturesLost();result.enemyCreaturesKilled = state.enemyCreaturesKilled();result.heroesKilled = state.heroesKilled();
        result.creatureKillsByPlayer = state.creatureKillsByPlayer().stream().mapToLong(Long::longValue).toArray();
        result.allies = new HashSet<>(state.allies());
        return result;
    }

    private static List<ResearchableEntity> restoreResearch(List<KeeperState.Research> states) {
        List<ResearchableEntity> restored = new ArrayList<>(states.size());
        for (var state : states) restored.add(state.restore());
        return restored;
    }

    @Override
    public void write(JmeExporter ex) throws IOException {
        OutputCapsule out = ex.getCapsule(this);
        out.write(id, "playerId", Integer.valueOf(0).shortValue());
        out.write(ai, "ai", false);
        out.write(aiType, "aiType", null);
        out.write(gold, "goldTotal", 0L);
        out.write(goldReserve, "goldReserve", 0);
        out.write(goldMined, "goldMined", 0);
        out.write(mana, "mana", 0);
        out.write(manaGain, "manaGain", 0);
        out.write(manaLoose, "manaLoose", 0);
        out.write(maxMana, "maxMana", 0);
        out.write(dungeonHeartLocation != null, "hasDungeonHeartLocation", true);
        out.write(dungeonHeartLocation != null ? dungeonHeartLocation.x : 0, "dungeonHeartLocationX", 0);
        out.write(dungeonHeartLocation != null ? dungeonHeartLocation.y : 0, "dungeonHeartLocationY", 0);
        out.writeSavableArrayList(new ArrayList(availableRooms), "availableRooms", null);
        out.writeSavableArrayList(new ArrayList(availableSpells), "availableSpells", null);
        out.write(toPrimitiveShortArray(availableCreatures), "availableCreatures", new short[0]);
        out.writeSavableArrayList(new ArrayList(availableDoors), "availableDoors", null);
        out.writeSavableArrayList(new ArrayList(availableTraps), "availableTraps", null);
        out.write(currentResearch, "currentResearch", null);
        out.write(destroyed, "destroyed", false);
        out.write(killedByPlayerId, "killedByPlayerId", (short) 0);
        out.write(toPrimitiveShortArray(allies), "allies", new short[0]);
        out.write(creaturesLost, "creaturesLost", 0L);
        out.write(enemyCreaturesKilled, "enemyCreaturesKilled", 0L);
        out.write(heroesKilled, "heroesKilled", 0L);
        out.write(creatureKillsByPlayer, "creatureKillsByPlayer", new long[256]);
    }

    @Override
    public void read(JmeImporter im) throws IOException {
        InputCapsule in = im.getCapsule(this);
        id = in.readShort("playerId", id);
        ai = in.readBoolean("ai", ai);
        aiType = in.readEnum("aiType", AIType.class, aiType);
        gold = in.readLong("goldTotal", in.readInt("gold", 0));
        goldReserve = in.readInt("goldReserve", 0);
        if (goldReserve < 0 || goldReserve > gold) throw new IOException("Invalid gold reserve");
        goldMined = in.readInt("goldMined", goldMined);
        mana = in.readInt("mana", mana);
        manaGain = in.readInt("manaGain", manaGain);
        manaLoose = in.readInt("manaLoose", manaLoose);
        maxMana = in.readInt("maxMana", maxMana);
        int x = in.readInt("dungeonHeartLocationX", 0);
        int y = in.readInt("dungeonHeartLocationY", 0);
        dungeonHeartLocation = in.readBoolean("hasDungeonHeartLocation", true) ? new Point(x, y) : null;
        availableRooms = in.readSavableArrayList("availableRooms", new ArrayList<>());
        availableSpells = in.readSavableArrayList("availableSpells", new ArrayList<>());
        availableCreatures = toReferenceList(in.readShortArray("availableCreatures", new short[0]));
        availableDoors = in.readSavableArrayList("availableDoors", new ArrayList<>());
        availableTraps = in.readSavableArrayList("availableTraps", new ArrayList<>());
        currentResearch = (ResearchableEntity) in.readSavable("currentResearch", null);
        destroyed = in.readBoolean("destroyed", destroyed);
        killedByPlayerId = destroyed ? in.readShort("killedByPlayerId", (short) 0) : 0;
        allies = new HashSet<>(toReferenceList(in.readShortArray("allies", new short[0])));
        creaturesLost = in.readLong("creaturesLost", 0L);
        enemyCreaturesKilled = in.readLong("enemyCreaturesKilled", 0L);
        heroesKilled = in.readLong("heroesKilled", 0L);
        creatureKillsByPlayer = in.readLongArray("creatureKillsByPlayer", new long[256]);
        if (creaturesLost < 0 || enemyCreaturesKilled < 0 || heroesKilled < 0
                || creatureKillsByPlayer == null || creatureKillsByPlayer.length != 256) {
            throw new IOException("Invalid creature combat statistics");
        }
        for (long count : creatureKillsByPlayer) {
            if (count < 0) throw new IOException("Invalid creature kill count");
        }
    }

    private static short[] toPrimitiveShortArray(Collection<Short> list) {
        short[] primList = new short[list.size()];
        int i = 0;
        for (Short value : list) {
            primList[i] = value;

            i++;
        }

        return primList;
    }

    private static List<Short> toReferenceList(short[] shortArray) {
        List<Short> list = new ArrayList(shortArray.length);
        for (short value : shortArray) {
            list.add(value);
        }

        return list;
    }

    @Override
    public int compareTo(Keeper keeper) {
        return Short.compare(id, keeper.id);
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 67 * hash + this.id;
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Keeper other = (Keeper) obj;
        if (this.id != other.id) {
            return false;
        }
        return true;
    }

}
