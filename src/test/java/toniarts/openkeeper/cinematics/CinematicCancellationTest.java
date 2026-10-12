/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.cinematics;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.audio.Listener;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinematicCancellationTest {
    @Test void cancellingPendingTransitionDoesNotMoveCameraOrListener() {
        var f=new Fixture();
        f.camera.setLocation(new Vector3f(8,9,10));f.listener.setLocation(new Vector3f(3,4,5));
        f.cinematic.cancel();f.cinematic.cancel();
        assertEquals(new Vector3f(8,9,10),f.camera.getLocation());
        assertEquals(new Vector3f(3,4,5),f.listener.getLocation());
        assertNull(f.app.getStateManager().getState(Cinematic.class));
    }
    @Test void normalStopStillAppliesFinalAuthoredCameraPose() {
        var f=new Fixture();f.cinematic.stop();
        assertEquals(new Vector3f(20,30,40),f.camera.getLocation());
        assertEquals(f.camera.getLocation(),f.listener.getLocation());
        assertNull(f.app.getStateManager().getState(Cinematic.class));
    }
    static class Fixture {
        final Camera camera=new Camera(1280,800);final Listener listener=new Listener();
        final SimpleApplication app=new SimpleApplication(){public void simpleInitApp(){}};
        final Cinematic cinematic;
        Fixture(){
            var data=new CameraSweepData(List.of(new CameraSweepDataEntry(new Vector3f(20,30,40),Quaternion.IDENTITY,60,.1f)));
            var assets=(AssetManager)Proxy.newProxyInstance(AssetManager.class.getClassLoader(),new Class<?>[]{AssetManager.class},(p,m,a)->data);
            cinematic=new Cinematic(assets,camera,listener,Vector3f.ZERO,"synthetic",new Node(),app.getStateManager());
            app.getStateManager().attach(cinematic);
        }
    }
}
