/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper;

import java.util.ArrayList;
import java.util.Arrays;

/** Internal app-bundle entry point, with GLFW JVM options supplied by jpackage. */
public final class GameLauncher {
    private GameLauncher() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Launch the MacKeeper application.");
        System.setProperty("mackeeper.home", args[0]);
        if (!args[1].isBlank()) System.setProperty("mackeeper.dk2.dir", args[1]);
        var gameArgs = new ArrayList<>(Arrays.asList(args).subList(2, args.length));
        gameArgs.add("-setup-complete");
        Main.main(gameArgs.toArray(String[]::new));
    }
}
