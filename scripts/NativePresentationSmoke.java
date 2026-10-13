/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.save.PresentationSnapshots;
import toniarts.openkeeper.game.save.CameraSnapshots;
import toniarts.openkeeper.game.save.MessageSnapshots;
import toniarts.openkeeper.game.save.HudPanelSnapshots;
import toniarts.openkeeper.gui.nifty.CustomTabGroupControl;
import toniarts.openkeeper.view.MessageState;
import toniarts.openkeeper.view.SystemMessageState;
import toniarts.openkeeper.gui.nifty.message.MessageBoxControl;
import toniarts.openkeeper.gui.nifty.message.SystemMessageControl;
import toniarts.openkeeper.view.PlayerCameraState;
import toniarts.openkeeper.view.camera.CameraState;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.*;

/** Owned rendered client owners; not a complete session replacement. */
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
        LocalGameSession session;GameClientState client;MessageState pausedMessages;
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
        private List<SystemMessageControl> messages() {
            return app.getNifty().getScreen("hud").findElementById("systemMessages").getChildren().stream()
                    .map(e -> e.getControl(SystemMessageControl.class)).filter(c -> c != null && !c.isRemoved()).toList();
        }
        private MessageBoxControl box() {
            return app.getNifty().getScreen("hud").findControl("messageBox", MessageBoxControl.class);
        }
        private void restoreHudPanels(PlayerState player) throws Exception {
            var controller = player.getScreen();var hud = app.getNifty().getScreen("hud");
            var tabs = hud.findControl("tabs-hud", CustomTabGroupControl.class);
            controller.showObjective(1);controller.toggleObjective();controller.togglePanel();
            controller.setCinematicText(2, false, 0);tabs.setSelectedTabIndex(1);controller.lastUpdate = .125f;
            controller.pauseMenuNavigate("CONFIRMATION", "QUIT", "Saved quit confirmation", "quitToOS()");
            var saved = controller.captureHudState();
            var messageState = states.getState(SystemMessageState.class).captureState();
            String objective = hud.findNiftyControl("scriptedObjective", de.lessvoid.nifty.controls.Label.class).getText();
            String caption = app.getNifty().getScreen("cinematic").findNiftyControl("speechText", de.lessvoid.nifty.controls.Label.class).getText();
            var events = new int[1];
            app.getNifty().subscribe(hud, "tabs-hud", de.lessvoid.nifty.controls.TabSelectedEvent.class, (topic, event) -> events[0]++);
            Path file = Files.createTempFile(Path.of("work"), "hud-panels-", ".bin");
            try {
                for (int i = 0; i < 3; i++) {
                    Files.write(file, HudPanelSnapshots.encode(saved));
                    controller.togglePanel();controller.toggleObjective();tabs.restoreSelectedTabIndex(0);
                    controller.pauseMenuNavigate("MAIN", null, null, null);
                    controller.restoreHudState(HudPanelSnapshots.decode(Files.readAllBytes(file)));
                    if (!saved.equals(controller.captureHudState()) || events[0] != 0 || !player.isPaused())
                        throw new AssertionError("HUD panels failed quiet restoration");
                    if (!objective.equals(hud.findNiftyControl("scriptedObjective", de.lessvoid.nifty.controls.Label.class).getText())
                            || !caption.equals(app.getNifty().getScreen("cinematic").findNiftyControl("speechText", de.lessvoid.nifty.controls.Label.class).getText())
                            || !hud.findElementById("optionsMenu").findNiftyControl("#title", de.lessvoid.nifty.controls.Label.class).getText().equals("Saved quit confirmation"))
                        throw new AssertionError("Restored HUD labels changed");
                    if (!messageState.equals(states.getState(SystemMessageState.class).captureState())) throw new AssertionError("Panel restoration changed message owner");
                }
            } finally {Files.delete(file);}
            System.out.println("HUD PASS objective/subtitle/tab/collapsed panel/confirmation restored through 3 disk round trips without tab callbacks or pause/message changes");
        }
        private void restoreMessages() throws Exception {
            var owner = states.getState(SystemMessageState.class);
            owner.restoreState(new MessageState(false, List.of(), MessageState.Box.closed()));
            owner.addMessageIcon(SystemMessageState.MessageType.INFO, "Saved first message");
            owner.addMessageIcon(SystemMessageState.MessageType.FIGHT, "Saved unread message");
            messages().getFirst().advanceTime(59);messages().getFirst().showMessage();
            pausedMessages = owner.captureState();
            if (pausedMessages.messages().getFirst().unread() || !pausedMessages.messages().getLast().unread()
                    || pausedMessages.box().messageIndex() != 0) throw new AssertionError("Message setup lost read/selection state");
            Path file = Files.createTempFile(Path.of("work"), "active-messages-", ".bin");
            try {
                for (int i = 0; i < 3; i++) {
                    Files.write(file, MessageSnapshots.encode(pausedMessages));
                    owner.restoreState(MessageSnapshots.decode(Files.readAllBytes(file)));
                    if (!pausedMessages.equals(owner.captureState())) throw new AssertionError("Message restore changed state");
                    String label = app.getNifty().getScreen("hud").findElementById("messageBox")
                            .findNiftyControl("#messageText", de.lessvoid.nifty.controls.Label.class).getText();
                    if (!label.equals("Saved first message")) throw new AssertionError("Restored message label missing: " + label);
                }
            } finally {Files.delete(file);}
            System.out.println("MESSAGES ordered read/unread icons and open text restored through 3 disk round trips");
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
                    restoreMessages();restoreHudPanels(player);stage = 4;delay = 0;
                }
                if (stage == 4 && (delay += tpf) > 2) {
                    if (!pausedMessages.equals(states.getState(SystemMessageState.class).captureState())) throw new AssertionError("Paused messages aged");
                    loop.resume();stage = 5;delay = 0;
                }
                if (stage == 5 && !player.isPaused() && (delay += tpf) > 2) {
                    var owner = states.getState(SystemMessageState.class);var expired = owner.captureState();
                    if (expired.messages().stream().anyMatch(m -> m.text().equals("Saved first message"))
                            || expired.messages().stream().noneMatch(m -> m.text().equals("Saved unread message")) || expired.box().detachedMessage() == null
                            || expired.box().detachedMessage().ageSeconds() != 60) throw new AssertionError("Open expired message was not retained: " + expired);
                    owner.restoreState(MessageSnapshots.decode(MessageSnapshots.encode(expired)));
                    if (!expired.equals(owner.captureState())) throw new AssertionError("Expired open message restore changed state");
                    box().dismissMessage();
                    if (!owner.captureState().messages().equals(expired.messages())) throw new AssertionError("Dismissal removed another icon");
                    int before = owner.captureState().messages().size();
                    owner.addMessage(SystemMessageState.MessageType.INFO, "Immediate delivery");
                    if (owner.captureState().messages().size() != before + 1) throw new AssertionError("Message delivery deferred outside snapshot state");
                    System.out.println("MESSAGES PASS pause retained lifetime, expiry retained open text, dismissal preserved next icon, direct delivery captured immediately");
                    System.out.println("PRESENTATION PASS pending events, active camera and message owners, cinematic acknowledgement and pause/resume");
                    successful = true;app.stop();stage = 6;
                }
            } catch (Throwable failure) {failure.printStackTrace();app.stop();stage = 6;}
        }
    }
}
