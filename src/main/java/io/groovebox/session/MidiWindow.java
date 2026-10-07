package io.groovebox.session;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a melody track takes its notes from a MIDI file: the file, the track inside it and the bar
 * the window opens on, counting from 0. How many bars it takes is the jam's loop length, not the
 * window's own, so every track of a jam is as long as the loop.
 */
public record MidiWindow(Path file, int trackIndex, int startBar) implements MelodySource {

    public MidiWindow {
        Objects.requireNonNull(file, "file");
        if (trackIndex < 0) {
            throw new IllegalArgumentException("Track index must not be negative");
        }
        if (startBar < 0) {
            throw new IllegalArgumentException("Start bar must not be negative");
        }
    }

    public MidiWindow withFile(Path next) {
        return new MidiWindow(next, trackIndex, startBar);
    }

    public MidiWindow withTrackIndex(int next) {
        return new MidiWindow(file, next, startBar);
    }

    public MidiWindow withStartBar(int next) {
        return new MidiWindow(file, trackIndex, next);
    }
}
