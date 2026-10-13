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
package toniarts.openkeeper.view.map;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import toniarts.openkeeper.utils.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import toniarts.openkeeper.view.WorldViewState;

/**
 * Simple app state that keeps tile flashing
 *
 * @author ArchDemon
 */
public final class FlashTileViewState extends AbstractAppState {

    private float tick = 0;
    public static final float FLASH_PERIOD = 0.5f;
    private boolean flashed = false;
    private final BiConsumer<Boolean, List<Point>> paint;
    private final Set<Point> painted = new HashSet<>();
    private AppStateManager states;
    private Thread renderThread;
    private final Set<Point> points = new HashSet<>();


    public FlashTileViewState(final MapViewController mapViewController) {
        this(mapViewController::flashTile);
    }

    FlashTileViewState(BiConsumer<Boolean, List<Point>> paint) {this.paint = paint;}

    public WorldViewState.Flash captureState() {
        requireOwner();return new WorldViewState.Flash(isEnabled(), tick, flashed, points, painted);
    }

    public void restoreState(WorldViewState.Flash saved) {
        requireOwner();
        paint.accept(false, new ArrayList<>(painted));
        paint.accept(true, new ArrayList<>(saved.painted()));
        points.clear();points.addAll(saved.points());painted.clear();painted.addAll(saved.painted());
        tick = saved.tick();flashed = saved.phase();setEnabled(saved.enabled());
    }

    private void requireOwner() {
        if (!isInitialized() || renderThread != Thread.currentThread() || states.getState(FlashTileViewState.class) != this)
            throw new IllegalStateException("Tile flashing belongs to the active render-thread map");
    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);

        states = stateManager;renderThread = Thread.currentThread();
    }

    public boolean attach(List<Point> points, boolean enabled) {
        requireOwner();
        if (enabled) return this.points.addAll(points);
        paint.accept(false, List.copyOf(points));painted.removeAll(points);
        return this.points.removeAll(points);
    }

    @Override
    public void update(float tpf) {
        if (!initialized || points.isEmpty()) {
            return;
        }

        if (tick > FLASH_PERIOD) {
            tick -= FLASH_PERIOD;
            flashed = !flashed;
            paint.accept(flashed, new ArrayList<>(points));
            painted.clear();if (flashed) painted.addAll(points);
        }

        tick += tpf;
    }

}
