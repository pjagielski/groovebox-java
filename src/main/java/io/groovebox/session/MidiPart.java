package io.groovebox.session;

/**
 * A note-bearing part of a MIDI file, identified by its file track number. Its first bar counts
 * from zero and its end is exclusive, just as a {@link MidiWindow}'s range does.
 */
public record MidiPart(int trackIndex, String name, int noteCount, int firstBar, int endBar) {
}
