/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.save.PresentationSnapshots;
import toniarts.openkeeper.game.save.CameraSnapshots;
import toniarts.openkeeper.view.PlayerCameraState;
import toniarts.openkeeper.view.camera.CameraState;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.*;

/** Owned rendered client: pending notifications only, not a complete session replacement. */
class NativePresentationSmoke {
    static volatile boolean successful;
    public static void main(String[] args) throws Exception {
        Thread.ofPlatform().daemon().name("presentation-watchdog").start(() -> {
            try {Thread.sleep(180_000);} catch (InterruptedException ignored) {return;}
            System.err.println("AssertionError: Native presentation flow timed out");System.exit(1);
        });
        var parse = Main.class.getDeclaredMethod("parseArguments", String[].class);parse.setAccessible(true);
        parse.invoke(null, (Object) new String[]{"-nomovies", "-level", "Level1"});
        var constructor = Main.class.getDeclaredConstructor();constructor.setAccessible(true);Main app = constructor.newInstance();
        var init = Main.class.getDeclaredMethod("initSettings", Main.class);init.setAccessible(true);init.invoke(null, app);
        app.setShowSettings(false);app.setPauseOnLostFocus(false);app.getStateManager().attach(new Probe());app.start();
        if (!successful) throw new AssertionError("Native presentation flow did not complete");
    }
    static class Probe extends AbstractAppState {
        Main app;AppStateManager states;int stage;float delay;GameLoopManager loop;long gold;
        LocalGameSession session;GameClientState client;
        @Override public void initialize(AppStateManager states, Application app) {
            super.initialize(states, app);this.states = states;this.app = (Main) app;
        }
        private void restoreActiveCamera() throws Exception {
            var camera = states.getState(PlayerCameraState.class);
            var saved = loop.captureSnapshot(game -> camera.captureState());
            if (saved.sweeps().size() != 1 || saved.sweeps().getFirst().playback().time() <= 0) throw new AssertionError("Missing active camera playback");
            var playback = saved.sweeps().getFirst().playback();
            var changed = new toniarts.openkeeper.cinematics.Cinematic.State(playback.sweepFile(), playback.start(), playback.time(), playback.speed(),
                    playback.playState(), playback.initialized(), playback.samples(), "0".repeat(64));
            var bad = new CameraState(saved.playerId(), saved.enabled(), saved.view(), saved.storedView(), saved.audioListener(), saved.cursorVisible(),
                    saved.transitionEnd(), saved.movements(), List.of(new CameraState.Sweep(changed, true)));
            try {camera.restoreState(bad);throw new AssertionError("Changed installed sweep accepted");}
            catch (IllegalArgumentException expected) {}
            if (!saved.equals(camera.captureState()) || !session.isInTransition()) throw new AssertionError("Failed camera restore changed active state");
            Path file = Files.createTempFile(Path.of("work"), "active-camera-", ".bin");
            try {
                for (int i = 0; i < 3; i++) {
                    Files.write(file, CameraSnapshots.encode(saved));var decoded = CameraSnapshots.decode(Files.readAllBytes(file));
                    camera.restoreState(decoded);
                    if (!saved.equals(loop.captureSnapshot(game -> camera.captureState())) || !session.isInTransition()) throw new AssertionError("Active camera staging replayed effects or lost state");
                }
            } finally {Files.delete(file);}
            System.out.println("CAMERA active EnginePath67 restored through 3 disk round trips without start/stop/ack effects; failed binding preserves live owner");
        }
        @Override public void update(float tpf) {
            try {
                var player = states.getState(PlayerState.class);
                if (stage == 0 && player != null && player.isInitialized() && player.isEnabled()) {
                    if ((delay += tpf) < 4) return;
                    client = states.getState(GameClientState.class);session = (LocalGameSession) client.getGameClientService();
                    var field = GameServerState.class.getDeclaredField("game");field.setAccessible(true);
                    loop = (GameLoopManager) field.get(states.getState(GameServerState.class));loop.pause();stage = 1;delay = 0;
                }
                if (stage == 1 && player.isPaused() && !session.isInTransition() && client.capturePendingPresentation().events().isEmpty()) {
                    var before = client.getPlayer();gold = before.getGold() + 123;int mana = before.getMana();
                    var origin = app.getCamera().getLocation().clone();
                    try (var worker = Executors.newSingleThreadExecutor()) {
                        worker.submit(() -> {
                            try {client.capturePendingPresentation();throw new AssertionError("Off-render capture accepted");}
                            catch (IllegalStateException expected) {}
                            session.onGoldChange((short) 3, gold, 12);session.onManaChange((short) 3, 321, 7, 9);
                            session.showObjective(1, (short) 3);session.setWidescreen(true, (short) 3);session.setWidescreen(false, (short) 3);
                            session.doTransition((short) 67, origin, (short) 3);
                        }).get(5, TimeUnit.SECONDS);
                    }
                    if (before.getGold() == gold || before.getMana() != mana) throw new AssertionError("Worker callback mutated client before render");
                    var pending = loop.captureSnapshot(game -> client.capturePendingPresentation());
                    if (pending.events().size() != 6 || !session.isInTransition()) throw new AssertionError("Missing queued events or server acknowledgement state: " + pending);
                    Path file = Files.createTempFile(Path.of("work"), "pending-presentation-", ".bin");
                    try {
                        for (int i = 0; i < 3; i++) {
                            Files.write(file, PresentationSnapshots.encode(pending));var restored = PresentationSnapshots.decode(Files.readAllBytes(file));
                            client.restorePendingPresentation(new PresentationQueue.State(List.of()));client.restorePendingPresentation(restored);
                            if (!pending.equals(loop.captureSnapshot(game -> client.capturePendingPresentation()))) throw new AssertionError("Queue changed during disk restore");
                            if (before.getGold() == gold || !session.isInTransition()) throw new AssertionError("Restore rendered or acknowledged event");
                        }
                    } finally {Files.delete(file);}
                    System.out.println("PRESENTATION 6 worker notifications retained through 3 disk round trips; no staging side effects");
                    stage = 2;delay = 0;
                }
                if (stage == 2 && (delay += tpf) > 1) {
                    if (client.getPlayer().getGold() != gold || client.getPlayer().getMana() != 321) throw new AssertionError("Client resources did not apply");
                    var hud = app.getNifty().getScreen("hud");
                    String mana = hud.findNiftyControl("mana", de.lessvoid.nifty.controls.Label.class).getText();
                    if (!mana.equals("321")) throw new AssertionError("Rendered mana label did not apply: " + mana);
                    if (!session.isInTransition() || player.isTransitionEnd()) throw new AssertionError("Restored cinematic never played");
                    if (!client.capturePendingPresentation().events().isEmpty()) throw new AssertionError("Delivered events stayed queued");
                    System.out.println("PRESENTATION restored HUD notifications rendered and EnginePath67 is playing");
                    restoreActiveCamera();stage = 3;
                }
                if (stage == 3 && (delay += tpf) > 7) {
                    if (session.isInTransition() || !player.isTransitionEnd()) throw new AssertionError("Restored cinematic did not acknowledge completion");
                    if (!player.isPaused()) throw new AssertionError("Pending event delivery changed pause state");
                    loop.resume();stage = 4;delay = 0;
                }
                if (stage == 4 && !player.isPaused()) {
                    System.out.println("PRESENTATION PASS pending and active camera restore, cinematic acknowledgement and pause/resume");
                    successful = true;app.stop();stage = 5;
                }
            } catch (Throwable failure) {failure.printStackTrace();app.stop();stage = 5;}
        }
    }
}
