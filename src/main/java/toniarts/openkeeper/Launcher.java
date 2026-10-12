/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 MacKeeper contributors
 */
package toniarts.openkeeper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import toniarts.openkeeper.setup.GameData;
import toniarts.openkeeper.utils.AppPaths;
import toniarts.openkeeper.utils.SettingUtils;

/** Runs Swing setup separately from GLFW's macOS first-thread rendering process. */
public final class Launcher {
    private Launcher() { }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws IOException, InterruptedException {
        List<String> gameArgs = new ArrayList<>(Arrays.asList(args));
        if (gameArgs.remove("--help")) {
            System.out.println("MacKeeper: an OpenKeeper-derived Dungeon Keeper II engine\n"
                    + "  --data-dir <directory>  Use your own installed or Steam-downloaded DK2 files\n"
                    + "  --check-assets          Check required files without opening a window\n"
                    + "  -nomovies               Skip intro movies\n"
                    + "  -level <name>           Load a level directly\n"
                    + "Original game data is required and is not included.");
            return 0;
        }
        boolean check = gameArgs.remove("--check-assets");
        String folder = System.getProperty("mackeeper.dk2.dir");
        int index = gameArgs.indexOf("--data-dir");
        if (index >= 0) {
            if (index + 1 >= gameArgs.size() || gameArgs.get(index + 1).startsWith("-")) {
                System.err.println("--data-dir needs a directory path.");
                return 2;
            }
            gameArgs.remove(index);
            folder = gameArgs.remove(index);
        }
        if (folder == null || folder.isBlank()) {
            String saved = SettingUtils.getInstance().getSettings().getString("DungeonKeeperIIFolder");
            if (saved != null && GameData.missingFiles(Path.of(saved)).isEmpty()) folder = saved;
        }
        if (folder == null || folder.isBlank()) {
            Path steam = Path.of(System.getProperty("user.home"), "Library/Application Support/Steam");
            folder = GameData.discoverSteamData(steam).map(Path::toString).orElse(null);
        }
        if (folder != null) {
            Path root = Path.of(folder).toAbsolutePath().normalize();
            List<String> missing = GameData.missingFiles(root);
            if (!missing.isEmpty()) {
                System.err.println("Incomplete Dungeon Keeper II data in " + root + ":\n  "
                        + String.join("\n  ", missing));
                return 2;
            }
            folder = root.toString();
        }
        if (check) {
            if (folder == null) {
                System.err.println("No Dungeon Keeper II data found. Use --data-dir <directory>.");
                return 2;
            }
            System.out.println("Required Dungeon Keeper II files found: " + folder
                    + "\nFile presence checked; conversion and gameplay still need validation.");
            return 0;
        }

        AppPaths.initialize();
        boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        int setupResult = start(childCommand(false, mac, folder, gameArgs), "setup.log");
        if (setupResult != 0) return setupResult;
        return start(childCommand(true, mac, folder, gameArgs), "game.log");
    }

    static List<String> childCommand(boolean game, boolean mac, String folder, List<String> args) {
        String bundleLauncher = System.getProperty("jpackage.app-path");
        if (game && mac && bundleLauncher != null) {
            var command = new ArrayList<String>();
            command.add(Path.of(bundleLauncher).resolveSibling("MacKeeperGame").toString());
            command.add(AppPaths.dataDirectory().toString());
            command.add(folder == null ? "" : folder);
            command.addAll(args);
            return List.copyOf(command);
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        if (game && mac) {
            command.add("-XstartOnFirstThread");
            command.add("-Djava.awt.headless=true");
        }
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dmackeeper.home=" + AppPaths.dataDirectory());
        if (folder != null) command.add("-Dmackeeper.dk2.dir=" + folder);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("toniarts.openkeeper.Main");
        command.addAll(args);
        command.add(game ? "-setup-complete" : "-setup-only");
        return List.copyOf(command);
    }

    private static int start(List<String> command, String logName) throws IOException, InterruptedException {
        Path log = AppPaths.dataDirectory().resolve("Logs").resolve(logName);
        System.out.println("MacKeeper log: " + log);
        Process child = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            return child.waitFor();
        } catch (InterruptedException e) {
            child.destroy();
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
