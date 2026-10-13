/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.app.state.AppStateManager;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.Main;
import static org.junit.jupiter.api.Assertions.*;

class LocalSessionLoaderTest {
    @Test void cancellationBeforeInitializationCompletesWithoutStartingIOOrTouchingApplicationServices() throws Exception {
        var constructor=Main.class.getDeclaredConstructor();constructor.setAccessible(true);var app=constructor.newInstance();
        var completion=new CompletableFuture<LocalSessionLoader.Result>();
        var create=LocalSessionLoader.class.getDeclaredConstructor(Main.class,Path.class,CompletableFuture.class);create.setAccessible(true);
        var loader=create.newInstance(app,Path.of("does-not-exist.mkls"),completion);
        var states=new AppStateManager(app);states.attach(loader);states.detach(loader);states.update(0);
        assertNull(states.getState(LocalSessionLoader.class));assertFalse(loader.isInitialized());
        assertTrue(completion.isCompletedExceptionally());assertThrows(CancellationException.class,completion::join);
        // Deliberately uninitialized Main has no Nifty, renderer or asset services: cancellation needs none.
        loader.cleanup();assertTrue(completion.isCompletedExceptionally());
    }
}
