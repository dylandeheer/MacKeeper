/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.audio.Listener;
import com.jme3.cinematic.PlayState;
import com.jme3.cinematic.events.CinematicEvent;
import com.jme3.cinematic.events.CinematicEventListener;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import toniarts.openkeeper.cinematics.*;
import toniarts.openkeeper.utils.WorldUtils;

/** Plays each converted owned camera file through the production cinematic, without rendering. */
class CameraPathSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply the converted Interface/Paths directory");
        int files = 0, samples = 0, stationary = 0;
        try (var paths = Files.list(Path.of(args[0]))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".csd")).sorted().toList()) {
                var data = (CameraSweepData) BinaryImporter.getInstance().load(path.toFile());
                var entries = data.getEntries();
                var assets = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                        new Class<?>[]{AssetManager.class}, (p, m, a) -> data);
                var camera = new Camera(1280, 800);
                var listener = new Listener();
                var origin = new Vector3f(7, 3, 11);
                var app = new SimpleApplication() { public void simpleInitApp() {} };
                var cinematic = new Cinematic(assets, camera, listener, origin, path.getFileName().toString().replace(".csd", ""), new Node(), app.getStateManager());
                var completions = new AtomicInteger();
                cinematic.addListener(new CinematicEventListener() {
                    public void onPlay(CinematicEvent event) {}
                    public void onPause(CinematicEvent event) {}
                    public void onStop(CinematicEvent event) { completions.incrementAndGet(); }
                });
                app.getStateManager().attach(cinematic);
                cinematic.play(); // Actual menu/game callers play before initialization.
                cinematic.initialize(app.getStateManager(), app);
                for (int frame = 0; frame < entries.size(); frame++) {
                    cinematic.update(frame / 30f - cinematic.getTime());
                    var expected = entries.get(frame);
                    var position = expected.getPosition().mult(WorldUtils.TILE_WIDTH).addLocal(origin);
                    if (position.distance(camera.getLocation()) > .003f
                            || Math.abs(expected.getRotation().dot(camera.getRotation())) < .9999f
                            || Math.abs(Math.tan(Math.toRadians(expected.getFov() / 2)) * .1 - camera.getFrustumTop()) > .00001
                            || !camera.getLocation().equals(listener.getLocation())
                            || !camera.getRotation().equals(listener.getRotation())) {
                        throw new AssertionError(path.getFileName() + " sample " + frame + " differs from the authored pose");
                    }
                }
                cinematic.update(1f / 30 + .001f);
                cinematic.stop();cinematic.cancel();cinematic.play();cinematic.update(1);
                if (cinematic.getPlayState() != PlayState.Stopped || completions.get() != 1
                        || app.getStateManager().getState(Cinematic.class) != null) {
                    throw new AssertionError(path.getFileName() + " did not finish once and detach");
                }
                files++;samples += entries.size();
                if (entries.stream().map(CameraSweepDataEntry::getPosition).distinct().count() == 1) stationary++;
            }
        }
        if (files == 0) throw new AssertionError("No converted camera paths found");
        System.out.printf("PASS %d camera paths, %d authored sample poses, %d stationary paths; completion once and detach%n", files, samples, stationary);
    }
}
