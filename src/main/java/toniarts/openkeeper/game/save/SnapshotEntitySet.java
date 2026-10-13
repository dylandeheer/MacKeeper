/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import com.simsilica.es.base.DefaultEntitySet;
import java.util.Set;
import java.util.TreeSet;

/** Server query with read-only access to queued change identities at the snapshot boundary. */
public final class SnapshotEntitySet extends DefaultEntitySet implements EntityComponentListener {
    private final DefaultEntityData data;
    private boolean closed;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public SnapshotEntitySet(EntityData data, Class... types) {
        super(data, null, types);
        if (!(data instanceof DefaultEntityData serverData)) throw new IllegalArgumentException("Snapshot query requires a server entity store");
        this.data = serverData;
        serverData.addEntityComponentListener(this);
        try {loadEntities(false);}
        catch (RuntimeException | Error failure) {serverData.removeEntityComponentListener(this);throw failure;}
    }

    @Override public void componentChange(EntityChange change) {entityChange(change);}

    /** Does not drain or apply the queue; same-value replacements remain observable. */
    public Set<EntityId> pendingChanges(Set<EntityId> observed) {
        if (closed) throw new IllegalStateException("Query is closed");
        var result = new TreeSet<EntityId>();
        for (var change : getChangeQueue()) if (observed.contains(change.getEntityId())) result.add(change.getEntityId());
        return Set.copyOf(result);
    }

    @Override public void release() {
        if (!closed) {
            closed = true;data.removeEntityComponentListener(this);
            super.release();
        }
    }
}
