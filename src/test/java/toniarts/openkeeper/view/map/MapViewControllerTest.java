/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package toniarts.openkeeper.view.map;

import org.junit.jupiter.api.Test;
import toniarts.openkeeper.view.map.WallSection.WallDirection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapViewControllerTest {

    @Test
    void evenXAndOddYDoNotAllowATorch() {
        assertEquals(List.of(), MapViewController.getTorchDirections(2, 3));
    }

    @Test
    void oddXAndOddYAllowNorthAndSouthWalls() {
        assertEquals(List.of(WallDirection.NORTH, WallDirection.SOUTH),
                MapViewController.getTorchDirections(3, 3));
    }

    @Test
    void evenXAndEvenYAllowWestAndEastWalls() {
        assertEquals(List.of(WallDirection.WEST, WallDirection.EAST),
                MapViewController.getTorchDirections(2, 4));
    }

    @Test
    void oddXAndEvenYAllowAllWallsInPriorityOrder() {
        assertEquals(List.of(WallDirection.NORTH, WallDirection.WEST, WallDirection.SOUTH, WallDirection.EAST),
                MapViewController.getTorchDirections(3, 4));
    }
    @Test
    void removingRoomsWithoutVisualNodesIsSafeAndClearsTheirCoordinates() throws Exception {
        MapViewController view = new MapViewController(null, null, null, (short)3, false) {
            @Override protected void updateProgress(float progress) {}
        };
        var scene = new com.jme3.scene.Node();
        var sceneField = MapViewController.class.getDeclaredField("roomsNode");
        sceneField.setAccessible(true);sceneField.set(view, scene);
        var roomConstructor = toniarts.openkeeper.tools.convert.map.Room.class.getDeclaredConstructor();
        roomConstructor.setAccessible(true);
        var hidden = new toniarts.openkeeper.common.RoomInstance(roomConstructor.newInstance());
        var visible = new toniarts.openkeeper.common.RoomInstance(roomConstructor.newInstance());
        var point = new toniarts.openkeeper.utils.Point(2,3);
        hidden.addCoordinate(point);
        var visibleNode = new com.jme3.scene.Node();scene.attachChild(visibleNode);
        var nodesField = MapViewController.class.getDeclaredField("roomNodes");nodesField.setAccessible(true);
        var nodes = (java.util.Map<toniarts.openkeeper.common.RoomInstance,com.jme3.scene.Spatial>)nodesField.get(view);
        nodes.put(hidden,null);nodes.put(visible,visibleNode);
        var coordinatesField = MapViewController.class.getDeclaredField("roomCoordinates");coordinatesField.setAccessible(true);
        var coordinates = (java.util.Map<toniarts.openkeeper.utils.Point,toniarts.openkeeper.common.RoomInstance>)coordinatesField.get(view);
        coordinates.put(point,hidden);
        view.removeRoomInstances(hidden,visible,hidden);
        assertEquals(0,scene.getQuantity());assertEquals(0,nodes.size());assertEquals(0,coordinates.size());
    }
}
