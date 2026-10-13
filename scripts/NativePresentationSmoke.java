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
import toniarts.openkeeper.game.save.InteractionSnapshots;
import toniarts.openkeeper.game.save.FogSnapshots;
import toniarts.openkeeper.game.save.CreatureCardSnapshots;
import toniarts.openkeeper.game.save.HandViewSnapshots;
import toniarts.openkeeper.view.HandViewState;
import toniarts.openkeeper.view.KeeperHandState;
import toniarts.openkeeper.view.creature.CreatureCardsState;
import toniarts.openkeeper.view.creature.CreatureCardRoster;
import toniarts.openkeeper.gui.nifty.WorkerAmountControl;
import toniarts.openkeeper.gui.nifty.CreatureCardControl;
import toniarts.openkeeper.view.PlayerMapViewState;
import toniarts.openkeeper.view.fogofwar.FogSnapshot;
import toniarts.openkeeper.game.fogofwar.FogState;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.InteractionSelection;
import toniarts.openkeeper.view.PlayerInteractionState;
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
        private static java.lang.reflect.Field interactionField(String name) throws Exception {
            var field = PlayerInteractionState.class.getDeclaredField(name);field.setAccessible(true);return field;
        }
        private void restoreHandView(PlayerState player) throws Exception {
            var interaction = states.getState(PlayerInteractionState.class);var hand = states.getState(KeeperHandState.class);
            var original = interaction.captureHandView();
            if (!original.items().isEmpty()) throw new AssertionError("Expected initially empty keeper hand");
            var candidates = player.getScreen().captureCreatureCards().creatures().stream()
                    .filter(c -> c.selectable() && c.type()==player.getKwdFile().getImp().getCreatureId()).limit(2).toList();
            if (candidates.size()!=2) throw new AssertionError("Need two actual workers for hand restore");
            for (var candidate : candidates) client.getGameClientService().pickUp(candidate.id());
            hand.update(0);hand.setVisible(true);
            var saved = interaction.captureHandView();
            if (saved.items().size()!=2 || !hand.getItem().getEntityId().equals(candidates.getLast().id())) throw new AssertionError("Actual pickups did not retain LIFO hand view");
            var serverBefore = loop.captureSnapshot(game -> game.getPlayerController(saved.playerId()).getHandControl().captureState());
            Path file = Files.createTempFile(Path.of("work"),"hand-view-",".bin");
            try {
                for (int i=0;i<3;i++) {
                    Files.write(file,HandViewSnapshots.encode(saved));var decoded = HandViewSnapshots.decode(Files.readAllBytes(file));
                    hand.setVisible(false);
                    loop.captureSnapshot(game -> {interaction.restoreHandView(decoded);return null;});
                    if (!saved.equals(interaction.captureHandView()) || !hand.getItem().getEntityId().equals(saved.items().getFirst().entityId())
                            || !serverBefore.equals(loop.captureSnapshot(game -> game.getPlayerController(saved.playerId()).getHandControl().captureState())))
                        throw new AssertionError("Hand view restore changed drop order or server-held objects");
                }
            } finally {Files.delete(file);}
            var wrongPlayer = new HandViewState((short)4,saved.capacity(),saved.enabled(),saved.visible(),saved.items());
            try {interaction.restoreHandView(wrongPlayer);throw new AssertionError("Wrong-player hand view accepted");} catch (IllegalArgumentException expected) {}
            if (!saved.equals(interaction.captureHandView())) throw new AssertionError("Invalid hand binding changed view");
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> {
                    try {interaction.captureHandView();throw new AssertionError("Off-render hand capture accepted");} catch (IllegalStateException expected) {}
                }).get(5,TimeUnit.SECONDS);
            }
            var tile = client.getPlayer().getDungeonHeartLocation();
            for (var item : saved.items()) client.getGameClientService().drop(item.entityId(),tile,new com.jme3.math.Vector2f(tile.x,tile.y),null);
            var serverEmpty = loop.captureSnapshot(game -> game.getPlayerController(saved.playerId()).getHandControl().captureState());
            if (!serverEmpty.bottomToTop().isEmpty()) throw new AssertionError("Actual drop did not empty server hand");
            // The displayed snapshot can lag the ECS. Restore must retain it until the normal query update.
            interaction.restoreHandView(saved);
            if (!saved.equals(interaction.captureHandView())) throw new AssertionError("Hand restore consumed pending removals");
            hand.update(0);
            if (!interaction.captureHandView().items().isEmpty() || hand.getItem()!=null
                    || !serverEmpty.equals(loop.captureSnapshot(game -> game.getPlayerController(saved.playerId()).getHandControl().captureState())))
                throw new AssertionError("Hand query did not reconcile pending removals quietly");
            interaction.restoreHandView(original);
            System.out.println("HAND PASS two real worker pickups, 3 hand-view disk restores with exact observed order and unchanged server hand, real drops and deferred removal reconciliation; invalid owner rejected");
        }
        private void restoreCreatureCards(PlayerState player) throws Exception {
            var controller = player.getScreen();var hud = app.getNifty().getScreen("hud");
            var managerField = PlayerScreenController.class.getDeclaredField("creatureCardManager");managerField.setAccessible(true);
            var manager = managerField.get(controller);var rosterField = manager.getClass().getDeclaredField("roster");rosterField.setAccessible(true);
            var roster = (CreatureCardRoster) rosterField.get(manager);
            var original = controller.captureCreatureCards();controller.workersAmount("JOBS");
            if (roster.selectNext(original.workerId(), null) == null) throw new AssertionError("Missing actual worker for cycling check");
            var saved = controller.captureCreatureCards();
            var expected = roster.selectNext(saved.workerId(), null);controller.restoreCreatureCards(saved);
            var cameraBefore = states.getState(PlayerCameraState.class).captureState();
            Path file = Files.createTempFile(Path.of("work"), "creature-cards-", ".bin");
            try {
                for (int i=0;i<3;i++) {
                    Files.write(file,CreatureCardSnapshots.encode(saved));controller.workersAmount("TOTAL");
                    var decoded = CreatureCardSnapshots.decode(Files.readAllBytes(file));
                    loop.captureSnapshot(game -> {controller.restoreCreatureCards(decoded);return null;});
                    if (!saved.equals(controller.captureCreatureCards())) throw new AssertionError("Creature observations/order/mode/cycling changed on restore");
                    if (!expected.equals(roster.selectNext(saved.workerId(),null))) throw new AssertionError("Next selected worker changed on restore");
                    String total = hud.findElementById("tab-workers").findNiftyControl("#workerTotal",de.lessvoid.nifty.controls.Label.class).getText();
                    if (!total.equals(Integer.toString(roster.count(saved.workerId(),null)))) throw new AssertionError("Rendered worker count changed");
                }
            } finally {Files.delete(file);}
            controller.restoreCreatureCards(saved);
            var wrongPlayer = new CreatureCardsState((short)4,saved.workerId(),saved.creatures(),saved.groups(),saved.cards(),saved.nextIndices(),saved.mode(),saved.firstVisible());
            try {controller.restoreCreatureCards(wrongPlayer);throw new AssertionError("Wrong-player cards accepted");} catch (IllegalArgumentException expectedFailure) {}
            if (!saved.equals(controller.captureCreatureCards())) throw new AssertionError("Failed creature binding changed cards");
            // Controlled historical projection exercises creation of a portrait even though Level1 currently has only workers.
            var species = player.getKwdFile().getCreatureList().stream().filter(c -> c.getCreatureId()!=saved.workerId() && c.getPortraitResource()!=null).findFirst().orElseThrow();
            var observed = saved.creatures().stream().filter(c -> c.selectable()).findFirst().orElseThrow();
            var portrait = new CreatureCardsState(saved.playerId(),saved.workerId(),
                    List.of(new CreatureCardsState.Observation(observed.id(),species.getCreatureId(),observed.position(),observed.activity(),false)),
                    List.of(new CreatureCardsState.Group(species.getCreatureId(),List.of(observed.id()))),
                    List.of(species.getCreatureId()),Map.of(),WorkerAmountControl.State.JOBS,0);
            loop.captureSnapshot(game -> {controller.restoreCreatureCards(portrait);return null;});
            var cardsField = manager.getClass().getDeclaredField("creatureCardControls");cardsField.setAccessible(true);
            @SuppressWarnings("unchecked") var cards = (Map<Short,CreatureCardControl>) cardsField.get(manager);
            var card = cards.get(species.getCreatureId());
            if (!card.getElement().findNiftyControl("#total",de.lessvoid.nifty.controls.Label.class).getText().equals("1")
                    || !card.getElement().findElementById("#tab-jobs").isVisible()) throw new AssertionError("Created portrait did not inherit saved mode/count");
            var empty = new CreatureCardsState(saved.playerId(),saved.workerId(),List.of(),List.of(),List.of(),Map.of(),WorkerAmountControl.State.JOBS,0);
            loop.captureSnapshot(game -> {
                controller.restoreCreatureCards(empty);var workers = hud.findNiftyControl("tab-workers",WorkerAmountControl.class);
                workers.zoomTo("null");workers.pickUp("null");return null;
            });
            if (!cameraBefore.equals(states.getState(PlayerCameraState.class).captureState())) throw new AssertionError("Restore or empty click moved the camera");
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> {
                    try {controller.captureCreatureCards();throw new AssertionError("Off-render cards capture accepted");} catch (IllegalStateException expectedFailure) {}
                }).get(5,TimeUnit.SECONDS);
            }
            controller.restoreCreatureCards(original);
            System.out.println("CARDS PASS three actual-worker disk restores preserve observations/cycling/mode and rendered counts; controlled portrait projection renders saved mode; empty worker actions are quiet; invalid owner rejected");
        }
        private void restoreFog() throws Exception {
            var owner = states.getState(PlayerMapViewState.class);
            var original = owner.captureFogState();var knowledge = original.knowledge();
            Point unexplored = null;
            for (int y = 1; y < knowledge.height() - 1 && unexplored == null; y++)
                for (int x = 1; x < knowledge.width() - 1; x++) {
                    var p = new Point(x,y);
                    if (!owner.getFogOfWarInformation().isVisible(p)) {unexplored = p;break;}
                }
            if (unexplored == null) throw new AssertionError("No unexplored fog test tile");
            owner.markPendingTaggedTiles(List.of(unexplored), true);
            var controller = (toniarts.openkeeper.view.fogofwar.FogOfWarController) owner.getFogOfWarInformation();
            var dirty = new Point(knowledge.width()/2, knowledge.height()/2);
            controller.revealActionPointTiles(List.of(dirty), !owner.getFogOfWarInformation().isExplored(dirty));
            var saved = owner.captureFogState();var messagesBefore = states.getState(SystemMessageState.class).captureState();
            Path file = Files.createTempFile(Path.of("work"), "fog-", ".bin");
            try {
                for (int i = 0; i < 3; i++) {
                    Files.write(file, FogSnapshots.encode(saved));var decoded = FogSnapshots.decode(Files.readAllBytes(file));
                    controller.disableFogOfWar();
                    loop.captureSnapshot(game -> {owner.restoreFogState(decoded);return null;});
                    if (!saved.equals(owner.captureFogState()) || !messagesBefore.equals(states.getState(SystemMessageState.class).captureState()))
                        throw new AssertionError("Fog restore changed discovery history or replayed notifications");
                    if (!owner.getFogOfWarInformation().isPendingTagged(unexplored)) throw new AssertionError("Pending fog tag lost");
                }
            } finally {Files.delete(file);}
            var k = saved.knowledge();
            var bad = new FogSnapshot(new FogState.Snapshot((short)4,k.width(),k.height(),k.enabled(),k.explored(),k.perceived(),k.dirty()),
                    saved.visionTime(),saved.hearts(),saved.pendingTags(),saved.seenEnemies(),saved.observedHearts(),saved.possessedCreature());
            try {owner.restoreFogState(bad);throw new AssertionError("Wrong-viewer fog accepted");} catch (IllegalArgumentException expected) {}
            if (!saved.equals(owner.captureFogState())) throw new AssertionError("Invalid fog binding changed owner");
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> {
                    try {owner.captureFogState();throw new AssertionError("Off-render fog capture accepted");} catch (IllegalStateException expected) {}
                }).get(5,TimeUnit.SECONDS);
            }
            owner.restoreFogState(original);
            System.out.println("FOG PASS three disk round trips retain knowledge, pending tags/dirty history and discovery timers, redraw actual map without notification replay; invalid owner rejected");
        }
        private void restoreInteraction() throws Exception {
            var owner = states.getState(PlayerInteractionState.class);
            short roomId = client.getPlayer().getAvailableRooms().getFirst().getId();
            owner.setInteractionState(PlayerInteractionState.InteractionState.Type.ROOM, roomId);owner.setEnabled(false);
            var saved = owner.captureSelection();
            var selection = (toniarts.openkeeper.view.selection.SelectionHandler) interactionField("selectionHandler").get(owner);
            @SuppressWarnings("unchecked") var keys = (Set<Integer>) interactionField("keys").get(owner);
            selection.setActive(true);keys.add(com.jme3.input.KeyInput.KEY_LCONTROL);
            Path file = Files.createTempFile(Path.of("work"), "interaction-selection-", ".bin");
            try {
                for (int i = 0; i < 3; i++) {
                    Files.write(file, InteractionSnapshots.encode(saved));var decoded = InteractionSnapshots.decode(Files.readAllBytes(file));
                    owner.setInteractionState(PlayerInteractionState.InteractionState.Type.NONE, 0);
                    loop.captureSnapshot(game -> {owner.restoreSelection(decoded);return null;});
                    if (!saved.equals(owner.captureSelection()) || selection.isActive() || !keys.isEmpty())
                        throw new AssertionError("Interaction selection/gesture restoration failed");
                }
            } finally {Files.delete(file);}
            for (var bad : List.of(new InteractionSelection((short)4, false, saved.type(), saved.itemId()),
                    new InteractionSelection(saved.playerId(), false, saved.type(), 32767))) {
                try {owner.restoreSelection(bad);throw new AssertionError("Invalid tool binding accepted");}
                catch (IllegalArgumentException expected) {}
                if (!saved.equals(owner.captureSelection())) throw new AssertionError("Invalid binding changed selected tool");
            }
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> {
                    try {owner.captureSelection();throw new AssertionError("Off-render interaction capture accepted");} catch (IllegalStateException expected) {}
                }).get(5, TimeUnit.SECONDS);
            }
            System.out.println("INTERACTION selected room/disabled state retained through 3 disk round trips inside world boundary; gestures cleared and invalid bindings preserved owner");
        }
        private void verifyInteractionResume() throws Exception {
            var owner = states.getState(PlayerInteractionState.class);var saved = owner.captureSelection();
            if (!saved.enabled() || saved.type() != PlayerInteractionState.InteractionState.Type.ROOM
                    || interactionField("refreshAfterRestore").getBoolean(owner)) throw new AssertionError("Interaction did not resume and refresh: " + saved + " pending=" + interactionField("refreshAfterRestore").getBoolean(owner));
            var input = (com.jme3.input.RawInputListener) interactionField("inputListener").get(owner);
            // Give injected release events a valid map context; no OS mouse movement is needed.
            interactionField("isOnGui").setBoolean(owner, false);interactionField("isOnMap").setBoolean(owner, true);
            input.onMouseButtonEvent(new com.jme3.input.event.MouseButtonEvent(com.jme3.input.MouseInput.BUTTON_RIGHT, false, 0, 0));
            if (!saved.equals(owner.captureSelection())) throw new AssertionError("Pre-load release cancelled restored tool");
            input.onMouseButtonEvent(new com.jme3.input.event.MouseButtonEvent(com.jme3.input.MouseInput.BUTTON_RIGHT, true, 0, 0));
            input.onMouseButtonEvent(new com.jme3.input.event.MouseButtonEvent(com.jme3.input.MouseInput.BUTTON_RIGHT, false, 0, 0));
            if (owner.captureSelection().type() != PlayerInteractionState.InteractionState.Type.NONE) throw new AssertionError("Fresh right click could not cancel the tool");
            System.out.println("INTERACTION PASS resumed pointer refresh, ignored stale release and accepted fresh tool-cancel click");
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
                    restoreMessages();restoreHudPanels(player);restoreInteraction();restoreFog();restoreCreatureCards(player);restoreHandView(player);stage = 4;delay = 0;
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
                    System.out.println("INTERACTION waiting for ordinary script/cinematic control return");
                    stage = 6;
                }
                if (stage == 6 && states.getState(PlayerInteractionState.class).isEnabled()
                        && !interactionField("refreshAfterRestore").getBoolean(states.getState(PlayerInteractionState.class))) {
                    verifyInteractionResume();
                    System.out.println("PRESENTATION PASS pending events, active camera, messages, HUD panels, interaction, fog, creature-card and hand-view owners, cinematic acknowledgement and pause/resume");
                    successful = true;app.stop();stage = 7;
                }
            } catch (Throwable failure) {failure.printStackTrace();app.stop();stage = 7;}
        }
    }
}
