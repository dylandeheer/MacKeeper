/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.IOException;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Manual and recovery save discovery. Names never become paths or executable UI actions. */
public final class SaveFiles {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
    public record Entry(Path path, String name, Instant modified, boolean recovery) {
        @Override public String toString() {return (recovery ? "Recovery" : name) + "   ·   " + DATE.format(modified);}
    }
    private final Path directory;
    public SaveFiles(Path profile) {directory = profile.toAbsolutePath().normalize().resolve("Saves");}
    public List<Entry> list(boolean recovery) throws IOException {
        Path folder = recovery ? directory.resolve("Recovery") : directory;
        if (!Files.exists(folder)) return List.of();
        var entries = new ArrayList<Entry>();
        try (var stream = Files.newDirectoryStream(folder)) {
            for (Path path : stream) {
                String file = path.getFileName().toString();
                if (file.toLowerCase(Locale.ROOT).endsWith(".mkls") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    entries.add(new Entry(path, file.substring(0,file.length()-5), Files.getLastModifiedTime(path).toInstant(), recovery));
            }
        }
        entries.sort(Comparator.comparing(Entry::modified).reversed().thenComparing(e -> e.path().toString()));
        return List.copyOf(entries);
    }
    public static String name(String value) {
        String name = Normalizer.normalize(Objects.requireNonNull(value).strip(), Normalizer.Form.NFC);
        if (name.toLowerCase(Locale.ROOT).endsWith(".mkls")) name = name.substring(0,name.length()-5);
        if (name.isEmpty() || name.length()>64 || !name.matches("[\\p{L}\\p{N} _.-]+")
                || name.startsWith(".") || name.endsWith(".") || name.endsWith(" ")
                || name.chars().noneMatch(Character::isLetterOrDigit))
            throw new IllegalArgumentException("Use 1–64 letters, numbers, spaces, dashes or underscores for the save name.");
        return name;
    }
    /** Resolve an existing case variant too, avoiding duplicate names across Mac/Linux installations. */
    public Path target(String value) throws IOException {
        String name = name(value);Path result = directory.resolve(name + ".mkls");
        if (Files.exists(directory)) try (var stream=Files.newDirectoryStream(directory)) {
            Path matched = null;
            for (Path entry:stream) if(entry.getFileName().toString().equalsIgnoreCase(name+".mkls")) {
                if(matched!=null) throw new IOException("More than one save has this name. Choose a different name.");
                if(!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)) throw new IOException("This save name is unavailable.");
                matched=entry;
            }
            if(matched!=null)result=matched;
        }
        return result;
    }
    /** New saves publish without replacing a file that appeared since the name check. */
    public void write(String name, LocalSessionArchive archive, boolean replace) throws IOException {
        Path target=target(name);
        if(replace){LocalSessionArchives.write(target,archive);return;}
        Files.createDirectories(directory);
        Path temporary=Files.createTempFile(directory,".new-save-",".tmp");
        try {
            LocalSessionArchives.write(temporary,archive);
            Files.createLink(target,temporary);
        } finally {Files.deleteIfExists(temporary);}
    }
}
