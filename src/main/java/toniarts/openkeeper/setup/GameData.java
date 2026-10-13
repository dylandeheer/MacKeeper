/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 MacKeeper contributors
 */
package toniarts.openkeeper.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Checks user-owned game data without loading the engine or modifying the installation. */
public final class GameData {
    public static final List<String> REQUIRED_FILES = List.of(
            "Data/editor/maps/FrontEnd3DLevel.kwd", "Data/Meshes.WAD", "Data/EngineTextures.WAD",
            "Data/Sprite.WAD", "Data/Paths.WAD", "Data/FrontEnd.WAD", "DK2TextureCache/EngineTextures.dat",
            "DK2TextureCache/EngineTextures.dir", "Data/Text/Default/Text.str",
            "Data/Text/Default/MBToUni.dat", "Data/Text/Default/FONT_FRONTEND20.bf4",
            "Data/Text/Default/FONT_INGAME10.bf4");

    private GameData() { }

    public static List<String> missingFiles(Path root) {
        List<String> missing = new ArrayList<>();
        for (String relative : REQUIRED_FILES) {
            Optional<Path> file = resolveIgnoringCase(root, relative);
            if (file.isEmpty() || !Files.isRegularFile(file.get()) || !Files.isReadable(file.get())) {
                missing.add(relative);
            }
        }
        return List.copyOf(missing);
    }

    public static Optional<Path> resolveIgnoringCase(Path root, String relative) {
        if (root == null) return Optional.empty();
        Path current = root;
        for (String segment : relative.split("/")) {
            if (!Files.isDirectory(current)) return Optional.empty();
            Path exact = current.resolve(segment);
            if (Files.exists(exact)) {
                current = exact;
                continue;
            }
            try (var children = Files.list(current)) {
                Optional<Path> found = children.filter(p -> p.getFileName().toString().equalsIgnoreCase(segment))
                        .sorted().findFirst();
                if (found.isEmpty()) return Optional.empty();
                current = found.get();
            } catch (IOException | SecurityException e) {
                return Optional.empty();
            }
        }
        return Optional.of(current.toAbsolutePath().normalize());
    }

    /** Includes Steam's console downloads; never scans unrelated user directories. */
    public static Optional<Path> discoverSteamData(Path steamRoot) {
        for (Path container : List.of(steamRoot.resolve("steamapps/common"),
                steamRoot.resolve("steamapps/content/app_2616460"))) {
            if (!Files.isDirectory(container)) continue;
            try (var children = Files.list(container)) {
                Optional<Path> match = children.filter(Files::isDirectory).sorted()
                        .filter(p -> missingFiles(p).isEmpty()).findFirst();
                if (match.isPresent()) return match;
            } catch (IOException | SecurityException ignored) {
                // An inaccessible library must not prevent manual selection.
            }
        }
        return Optional.empty();
    }
}
