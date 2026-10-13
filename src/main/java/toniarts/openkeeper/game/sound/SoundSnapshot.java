/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.sound;

import java.util.*;

/** Audio observations and future start actions. No listener, renderer or session object is persisted. */
public record SoundSnapshot(short playerId, String levelCategory, boolean enabled, String backgroundCategory,
        List<String> playlist, int nextTrack, List<Speech> pending, ActiveSpeech speech, Playback background) {
    public static final int MAX_ENTRIES=4096;
    public SoundSnapshot {
        Objects.requireNonNull(levelCategory);if(!levelCategory.isEmpty()) category(levelCategory);category(backgroundCategory);
        playlist=List.copyOf(playlist);pending=List.copyOf(pending);
        if(playerId<0 || playerId>255 || playlist.isEmpty() || playlist.size()>MAX_ENTRIES
                || pending.size()>MAX_ENTRIES || nextTrack<0 || nextTrack>=playlist.size())
            throw new IllegalArgumentException("Invalid audio owner/playlist");
        playlist.forEach(SoundSnapshot::file);
        if(!enabled && ((speech!=null && speech.playback().status()==Status.PLAYING) || (background!=null && background.status()==Status.PLAYING)))
            throw new IllegalArgumentException("Disabled audio cannot be playing");
        if(background!=null && !playlist.contains(background.file())) throw new IllegalArgumentException("Background is outside playlist");
    }
    public record StartAction(boolean showText, boolean introduction, int pathId) {}
    public record Speech(String category, boolean global, int id, String file, StartAction start) {
        public Speech {
            SoundSnapshot.category(category);SoundSnapshot.file(file);
            if(id<0) throw new IllegalArgumentException("Invalid speech ID");
        }
    }
    public enum Status { PLAYING, PAUSED }
    public record Playback(String file, String fingerprint, float seconds, Status status, float volume) {
        public Playback {
            SoundSnapshot.file(file);Objects.requireNonNull(status);
            if(fingerprint==null || !fingerprint.matches("[0-9a-f]{64}") || !Float.isFinite(seconds) || seconds<0
                    || !Float.isFinite(volume) || volume<0) throw new IllegalArgumentException("Invalid audio playback");
        }
    }
    public record ActiveSpeech(Speech speech, Playback playback) {
        public ActiveSpeech {
            Objects.requireNonNull(speech);Objects.requireNonNull(playback);
            if(!speech.file().equals(playback.file())) throw new IllegalArgumentException("Speech clip mismatch");
        }
    }
    public static void category(String value) {
        if(value==null || value.isEmpty() || value.length()>128 || !value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid sound category");
    }
    public static void file(String value) {
        if(value==null || value.isEmpty() || value.length()>1024 || value.startsWith("/") || value.contains("\\")
                || value.contains(":") || value.indexOf('\0')>=0 || Arrays.stream(value.split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")))
            throw new IllegalArgumentException("Invalid relative sound asset");
    }
}
