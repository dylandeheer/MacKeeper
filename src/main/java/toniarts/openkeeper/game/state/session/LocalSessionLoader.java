/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.utils.AppPaths;

/** Render-thread handoff with background preparation and a durable recovery checkpoint before retirement. */
public final class LocalSessionLoader extends AbstractAppState {
    public record Result(boolean loaded, boolean recovered, Path recoveryFile, String error) {}
    private enum Phase { PREPARING, WRITING_RECOVERY, WAITING_CLEANUP, ACTIVATING, PREPARING_RECOVERY, RECOVERING }
    @FunctionalInterface private interface Work<T> {T run() throws Exception;}
    private final Main app;
    private final Path source;
    private final CompletableFuture<Result> completion;
    private AppStateManager states;
    private GameClientState original, receiving;
    private GameServerState originalServer;
    private IKwdFile originalDefinition;
    private GameServerState.HeldSession held;
    private Path recoveryFile;
    private LocalGameSession.PreparedRestore prepared;
    private CompletableFuture<LocalGameSession.PreparedRestore> preparation;
    private CompletableFuture<Void> writing;
    private Phase phase = Phase.PREPARING;
    private Throwable loadFailure;
    private boolean retired, finished, inputHeld, priorMouse, priorKeyboard, priorSound;
    private SoundState heldSound;
    private volatile boolean closing;

    /** Safe from UI callbacks or other threads: admission and every lifecycle mutation run on the render thread. */
    public static CompletionStage<Result> load(Main app, Path source) {
        java.util.Objects.requireNonNull(app);
        Path target = java.util.Objects.requireNonNull(source).toAbsolutePath().normalize();
        var result = new CompletableFuture<Result>();
        app.enqueue(() -> {
            var states = app.getStateManager();
            if (states.getState(LocalSessionLoader.class) != null) {
                result.completeExceptionally(new IllegalStateException("A saved game is already loading"));return;
            }
            states.attach(new LocalSessionLoader(app, target, result));
        });
        return result.minimalCompletionStage();
    }

    private LocalSessionLoader(Main app, Path source, CompletableFuture<Result> completion) {
        this.app = app;this.source = source;this.completion = completion;
    }

    @Override public void initialize(AppStateManager states, Application ignored) {
        super.initialize(states, app);this.states = states;
        try {
            original = states.getState(GameClientState.class);originalServer = states.getState(GameServerState.class);
            if (original != null) {
                if (!(original.getGameClientService() instanceof LocalGameSession) || originalServer == null
                        || !original.isInitialized() || !original.isReadyForReplacement()
                        || !original.loadingCompletion().toCompletableFuture().isDone())
                    throw new IllegalStateException("Only a fully loaded local game can be replaced");
                originalDefinition = original.getLevelData();
            } else if (originalServer != null || states.getState(PlayerState.class) != null || app.getNifty().getScreen("hud") != null)
                throw new IllegalStateException("The previous game is still closing");
            preparation = prepare(() -> {
                var archive = LocalSessionArchives.read(source);
                return LocalGameSession.prepareLocalGame(archive, archive.resolveDefinition(Path.of(Main.getDkIIFolder())));
            });
        } catch (RuntimeException failure) {fail(failure);}
    }

    private <T> CompletableFuture<T> background(Work<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {return work.run();}
            catch (Exception failure) {throw new CompletionException(failure);}
        }, command -> Thread.ofVirtual().name("MacKeeper save loading").start(command));
    }

    private CompletableFuture<LocalGameSession.PreparedRestore> prepare(Work<LocalGameSession.PreparedRestore> work) {
        var future = background(work);
        future.thenAccept(value -> {if (closing) value.close();});
        return future;
    }

    @Override public void update(float tpf) {
        if (closing || finished) return;
        try {
            if (!retired && (states.getState(GameClientState.class) != original || states.getState(GameServerState.class) != originalServer))
                throw new IllegalStateException("The current game changed while preparing the saved game");
            switch (phase) {
                case PREPARING -> {
                    if (!preparation.isDone()) return;
                    prepared = preparation.join();
                    if (original == null) {holdInput();phase = Phase.WAITING_CLEANUP;retired = true;return;}
                    held = originalServer.holdLocalSession(original);
                    original.setTransferBlocked(true);
                    holdInput();
                    heldSound = states.getState(SoundState.class);
                    if (heldSound != null) {priorSound = heldSound.isEnabled();heldSound.setEnabled(false);}
                    recoveryFile = AppPaths.dataDirectory().resolve("Saves/Recovery/" + UUID.randomUUID() + ".mkls");
                    writing = background(() -> {LocalSessionArchives.writeRecovery(recoveryFile, held.archive());return null;});
                    phase = Phase.WRITING_RECOVERY;
                }
                case WRITING_RECOVERY -> {
                    if (!writing.isDone()) return;
                    writing.join();
                    // Only a successful durable write grants permission to retire the current world.
                    retired = true;original.detach();held.release().run();
                    phase = Phase.WAITING_CLEANUP;
                }
                case WAITING_CLEANUP -> {
                    if (!clean()) return;
                    receiving = prepared.install(states, app);prepared = null;
                    phase = Phase.ACTIVATING;
                }
                case ACTIVATING, RECOVERING -> {
                    var ready = receiving.loadingCompletion().toCompletableFuture();
                    if (!ready.isDone()) return;
                    ready.join();
                    if (states.getState(GameClientState.class) != receiving)
                        throw new IllegalStateException("The loaded session lost ownership");
                    boolean recovered = phase == Phase.RECOVERING;
                    finish(new Result(!recovered, recovered, recoveryFile, recovered ? message(loadFailure) : null));
                }
                case PREPARING_RECOVERY -> {
                    if (!preparation.isDone() || !clean()) return;
                    prepared = preparation.join();receiving = prepared.install(states, app);prepared = null;
                    phase = Phase.RECOVERING;
                }
            }
        } catch (RuntimeException failure) {fail(failure);}
    }

    private boolean clean() {
        return states.getState(GameClientState.class) == null && states.getState(GameServerState.class) == null
                && states.getState(PlayerState.class) == null && app.getNifty().getScreen("hud") == null;
    }

    private void fail(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (!retired) {
            releaseOriginal();
            finish(new Result(false, false, recoveryFile, message(failure)));return;
        }
        if (receiving != null) {receiving.detach();receiving = null;}
        if (loadFailure == null && held != null) {
            loadFailure = failure;
            if (prepared != null) {prepared.close();prepared = null;}
            preparation = prepare(() -> LocalGameSession.prepareLocalGame(held.archive(), originalDefinition));
            phase = Phase.PREPARING_RECOVERY;
        } else {
            if (loadFailure != null && failure != loadFailure) failure.addSuppressed(loadFailure);
            var menu = states.getState(MainMenuState.class);
            if (menu != null) menu.setEnabled(true);
            finish(new Result(false, false, recoveryFile, message(failure)));
        }
    }

    private void holdInput() {
        priorMouse = app.getNifty().isIgnoreMouseEvents();priorKeyboard = app.getNifty().isIgnoreKeyboardEvents();
        app.getNifty().setIgnoreMouseEvents(true);app.getNifty().setIgnoreKeyboardEvents(true);inputHeld = true;
    }

    private void releaseOriginal() {
        if (held != null) held.release().run();
        if (original != null && states.getState(GameClientState.class) == original && held != null) original.setTransferBlocked(false);
        if (heldSound != null) heldSound.setEnabled(priorSound);
    }

    private void finish(Result result) {
        finished = true;restoreInput();completion.complete(result);states.detach(this);
    }

    private void restoreInput() {
        if (!inputHeld) return;
        app.getNifty().resetMouseInputEvents();
        app.getNifty().setIgnoreMouseEvents(priorMouse);app.getNifty().setIgnoreKeyboardEvents(priorKeyboard);inputHeld = false;
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override public void stateDetached(AppStateManager manager) {
        super.stateDetached(manager);
        // AppStateManager does not call cleanup for a state cancelled before initialization.
        if (!isInitialized()) cleanup();
    }

    @Override public void cleanup() {
        closing = true;
        if (!finished) {
            if (!retired) releaseOriginal();
            else if (receiving != null) receiving.detach();
            completion.completeExceptionally(new CancellationException("Saved-game loading cancelled; any recovery file is retained"));
        }
        if (prepared != null) prepared.close();
        // Closing may race an unfinished worker; both sides close its single-use result safely.
        if (preparation != null) preparation.thenAccept(LocalGameSession.PreparedRestore::close);
        restoreInput();super.cleanup();
    }
}
