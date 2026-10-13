/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import java.util.*;
import java.util.stream.Stream;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;

/** Client-observed keeper values and the notifications still waiting to update them. */
public record ClientModelState(short playerId,List<KeeperState> keepers,PresentationQueue.State pending) {
    public ClientModelState {
        keepers=List.copyOf(keepers);Objects.requireNonNull(pending);
        if(playerId<0 || playerId>255 || keepers.isEmpty() || keepers.size()>256) throw new IllegalArgumentException("Invalid client keeper roster");
        int previous=-1;boolean found=false;
        for(var keeper:keepers) {
            if(keeper.id()<=previous) throw new IllegalArgumentException("Client keepers must be unique and ordered");
            previous=keeper.id();found|=keeper.id()==playerId;
        }
        if(!found) throw new IllegalArgumentException("Missing local keeper");
    }
    /** Prepare every binding before touching a live client or attaching views. */
    public Map<Short,Keeper> bind(IKwdFile definition) {
        var prepared=new TreeMap<Short,Keeper>();
        for(var saved:keepers) {
            Stream.concat(Stream.of(saved.availableRooms(),saved.availableSpells(),saved.availableDoors(),saved.availableTraps()).flatMap(Collection::stream),
                    saved.currentResearch()==null?Stream.empty():Stream.of(saved.currentResearch())).forEach(item -> validateResearch(item,definition));
            for(short id:saved.availableCreatures()) if(definition.getCreature(id)==null) throw new IllegalArgumentException("Missing client creature definition");
            prepared.put(saved.id(),Keeper.restoreState(saved,definition.getPlayer(saved.id())));
        }
        for(var event:pending.events()) if(event instanceof PresentationEvent.ResearchUpdate update) {
            if(!prepared.containsKey(update.keeperId())) throw new IllegalArgumentException("Pending research has no client keeper");
            validateResearch(update.research(),definition);
        }
        return Collections.unmodifiableMap(prepared);
    }
    private static void validateResearch(KeeperState.Research item,IKwdFile definition) {
        Object type=switch(item.type()) {
            case ROOM -> definition.getRoomById(item.id());case SPELL -> definition.getKeeperSpellById(item.id());
            case DOOR -> definition.getDoorById(item.id());case TRAP -> definition.getTrapById(item.id());
        };
        if(type==null) throw new IllegalArgumentException("Missing client research definition");
    }
    /** Values only: no HUD actions, commands, query advancement or pending event delivery. */
    public void restore(Map<Short,Keeper> live,PresentationQueue notifications,IKwdFile definition) {
        var prepared=bind(definition);
        if(!live.keySet().equals(prepared.keySet())) throw new IllegalArgumentException("Client keeper roster differs");
        for(var entry:live.entrySet()) if(entry.getValue()==null || entry.getValue().getId()!=entry.getKey()) throw new IllegalArgumentException("Invalid live client keeper binding");
        notifications.capture(); // Reject a closed or currently dispatching queue before changing values.
        for(var saved:keepers) live.get(saved.id()).restoreValues(saved,prepared.get(saved.id()).getPlayer());
        notifications.restore(pending);
    }
}
