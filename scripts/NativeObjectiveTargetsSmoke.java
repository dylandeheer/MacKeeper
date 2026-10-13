/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.app.Application;
import com.jme3.app.state.*;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import de.lessvoid.nifty.controls.Label;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.LevelObjectives;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.LocalGameSession;
import toniarts.openkeeper.game.state.session.LocalSessionLoader;
import toniarts.openkeeper.utils.WorldUtils;
import toniarts.openkeeper.view.PlayerCameraState;

/** Native objective labels, fog-aware locate, shared slots, and fresh save loading using owned assets. */
class NativeObjectiveTargetsSmoke {
    static boolean successful;
    static void check(boolean value, String message) {if (!value) throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        Thread.ofPlatform().daemon().start(() -> {try {Thread.sleep(240_000);} catch (InterruptedException e) {return;} System.err.println("AssertionError: Objective probe timed out"); System.exit(1);});
        var parse = Main.class.getDeclaredMethod("parseArguments", String[].class); parse.setAccessible(true); parse.invoke(null, (Object) new String[]{"-nomovies"});
        var ctor = Main.class.getDeclaredConstructor(); ctor.setAccessible(true); Main app = ctor.newInstance();
        var init = Main.class.getDeclaredMethod("initSettings", Main.class); init.setAccessible(true); init.invoke(null, app);
        app.setShowSettings(false); app.setPauseOnLostFocus(false); app.getStateManager().attach(new Probe()); app.start();
        if (!successful) throw new AssertionError("Native objective targets did not complete");
    }
    static class Probe extends AbstractAppState {
        Main app; AppStateManager states; int stage; float delay; EntityId visible, hidden;
        Vector3f location, before; List<String> labels; List<LevelObjectives.Target> savedTargets;
        CompletableFuture<LocalSessionLoader.Result> load;
        @Override public void initialize(AppStateManager manager, Application application) {
            super.initialize(manager, application);
            application.getListener().setVolume(0); // Keep native playback running without audible test output.
            app = (Main) application; states = manager;

        }
        void next(int value) {stage = value; delay = 0; System.out.println("TARGETS stage=" + value);}
        PlayerScreenController ui() {return (PlayerScreenController) app.getNifty().getScreen("hud").getScreenController();}
        Label label(int slot) {return app.getNifty().getScreen("hud").findNiftyControl("objectiveTarget" + slot, Label.class);}
        void click(int slot) {app.getNifty().getScreen("hud").findElementById("objectiveTarget" + slot).onClick();}
        List<String> labels() {var result = new ArrayList<String>(); for (int slot = 0; slot < 6; slot++) result.add(label(slot).getText()); return result;}
        GameLoopManager world() throws Exception {var field = GameServerState.class.getDeclaredField("game"); field.setAccessible(true); return (GameLoopManager) field.get(states.getState(GameServerState.class));}
        @Override public void update(float tpf) {try {
            delay += tpf;
            var client = states.getState(GameClientState.class); var player = states.getState(PlayerState.class); var menu = states.getState(MainMenuState.class);
            if (stage == 0) {
                if (menu == null || !menu.isInitialized() || app.getNifty().getCurrentScreen() == null
                        || !app.getNifty().getCurrentScreen().getScreenId().equals(MainMenuScreenController.SCREEN_START_ID)) return;
                menu.setEnabled(false); LocalGameSession.createLocalGame("Level12", true, states, app, new CampaignLevel(CampaignLevel.LevelType.Level, 12)); next(1); return;
            }
            if (stage == 1) {
                var camera = states.getState(PlayerCameraState.class);
                if (client == null || !client.loadingCompletion().toCompletableFuture().isDone() || delay < 8
                        || player == null || !player.isEnabled() || camera == null || !camera.isEnabled()) return;
                client.loadingCompletion().toCompletableFuture().join();
                if (!app.getNifty().getCurrentScreen().getScreenId().equals("hud")) {
                    camera.onAction(toniarts.openkeeper.game.data.Settings.Setting.USE_ATTACK.name(), true, 0); return;
                }
                player.setPaused(true); next(2); return;
            }
            if (stage == 2) {
                if (!player.isPaused() || delay < .5) return;
                var world = world(); var data = world.getEntityData(); var game = world.getGameController();
                var ids = data.findEntities(null, CreatureComponent.class, Position.class).stream().sorted().toList();
                visible = ids.stream().filter(id -> client.getFogOfWarInformation().isVisible(WorldUtils.vectorToPoint(data.getComponent(id, Position.class).position))).findFirst().orElseThrow();
                hidden = ids.stream().filter(id -> !client.getFogOfWarInformation().isVisible(WorldUtils.vectorToPoint(data.getComponent(id, Position.class).position))).findFirst().orElseThrow();
                location = data.getComponent(visible, Position.class).position.clone();
                world.runPlayerCommand(() -> {
                    for (var target : LevelObjectives.targets(data)) data.removeEntity(target.entryId());
                    for (var id : data.findEntities(null, PlayerObjective.class)) data.removeComponent(id, PlayerObjective.class);
                    game.markPlayerObjective(player.getPlayerId());
                    game.markCreatureObjective(visible, ObjectiveType.KILL);
                    game.markCreatureObjective(hidden, ObjectiveType.CONVERT);
                    game.markCreatureObjective(visible, ObjectiveType.IMPRISON);
                    game.markCreatureObjective(visible, ObjectiveType.CONVERT);
                    game.markPlayerObjective(game.getLevelInfo().getPlayers().keySet().stream().filter(id -> id > 3).sorted().findFirst().orElseThrow());
                    game.markCreatureObjective(visible, ObjectiveType.KILL); // Full registry: no overwrite.
                });
                check(LevelObjectives.targets(data).size() == 6, "Wrong shared capacity");
                app.getNifty().getScreen("hud").findElementById("optionsMenu").findElementById("#columnOne").getChildren().getFirst().onClick();
                next(3); return;
            }
            if (stage == 3) {
                if (delay < 1) return;
                labels = labels(); check(labels.stream().allMatch(s -> !s.isBlank()), "Missing target row " + labels);
                check(labels.get(0).startsWith("Defeat ") && labels.get(1).startsWith("Kill ") && labels.get(3).startsWith("Imprison ") && labels.get(4).startsWith("Convert "), "Wrong target labels " + labels);
                check(labels.get(1).contains("click to locate") && labels.get(2).contains("location unknown"), "Fog labels incorrect " + labels);
                check(app.getNifty().getScreen("hud").findElementById("objective").isVisible()
                        && !app.getNifty().getScreen("hud").findElementById("optionsMenu").isVisible() && player.isPaused(), "Pause-menu objective routing failed");
                before = states.getState(PlayerCameraState.class).getCamera().getLookAt().clone();
                click(2); next(31); return;
            }
            if (stage == 31) {
                if (delay < .3) return;
                check(states.getState(PlayerCameraState.class).getCamera().getLookAt().distance(before) < .001, "Hidden target moved camera");
                click(1); next(32); return;
            }
            if (stage == 32) {
                if (delay < .3) return;
                var after = states.getState(PlayerCameraState.class).getCamera().getLookAt();
                check(Math.abs(after.x - location.x) < .01 && Math.abs(after.z - location.z) < .01, "Visible target not located: " + after + " vs " + location);
                var panel = app.getNifty().getScreen("hud").findElementById("objective");
                for (int slot = 0; slot < 6; slot++) {
                    var row = app.getNifty().getScreen("hud").findElementById("objectiveTarget" + slot);
                    check(row.getHeight() > 0 && row.getY() >= panel.getY() && row.getY() + row.getHeight() <= app.getNifty().getScreen("hud").findElementById("objectiveClose").getY(), "Target row clipped " + slot);
                }
                var shot = states.getState(ScreenshotAppState.class); shot.setFilePath(Path.of("work").toAbsolutePath() + "/"); shot.setFileName("native-objective-targets"); shot.setIsNumbered(false); shot.takeScreenshot();
                next(4); return;
            }
            if (stage == 4) {
                if (delay < .5) return;
                savedTargets = LevelObjectives.targets(world().getEntityData());
                var file = Path.of("work/native-objective-targets.mkls"); LocalSessionArchives.write(file, states.getState(GameServerState.class).captureLocalSession(client));
                load = LocalSessionLoader.load(app, file).toCompletableFuture(); next(5); return;
            }
            if (stage == 5) {
                if (!load.isDone() || delay < 1) return;
                check(load.join().loaded(), "Objective save failed to load");
                check(savedTargets.equals(LevelObjectives.targets(world().getEntityData())), "Target registry changed after load");
                if (!app.getNifty().getScreen("hud").findElementById("objective").isVisible()) ui().toggleObjective();
                next(6); return;
            }
            if (stage == 6) {
                if (delay < 1) return;
                check(labels.equals(labels()), "Restored labels differ " + labels());
                var world = world(); world.runPlayerCommand(() -> world.getGameController().markCreatureObjective(visible, null)); next(7); return;
            }
            if (stage == 7) {
                if (delay < .6) return;
                check(label(1).getText().isBlank() && !label(3).getText().isBlank() && !label(0).getText().isBlank(), "Off erased wrong target");
                check(world().getEntityData().getComponent(visible, PlayerObjective.class).objective == ObjectiveType.IMPRISON, "Remaining target marker incorrect");
                ui().toggleObjective();
                check(app.getNifty().getScreen("hud").findElementById("optionsMenu").isVisible() && player.isPaused(), "Closing objectives lost paused menu");
                System.out.println("TARGETS PASS pause-menu routing, six rows, full capacity, fog-safe locate, actual camera movement, fresh save loading and first-match removal");
                successful = true; app.stop();
            }
        } catch (Throwable error) {error.printStackTrace(); app.stop();} }
    }
}
