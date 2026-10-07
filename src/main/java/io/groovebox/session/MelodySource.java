package io.groovebox.session;

import io.groovebox.model.Note;

import java.util.List;
import java.util.Objects;

/**
 * Where a melody track's notes come from: a window of a MIDI file, read afresh whenever the file,
 * the track or the loop changes, or the track's {@link OwnNotes}, which are only the track's and
 * are what the piano roll edits.
 *
 * <p>A window becomes the track's own the first time a hand changes one of its notes — see
 * {@link OwnNotes#takenFrom} — and the file is from then on only where they came from.
 */
public sealed interface MelodySource permits MidiWindow, MelodySource.OwnNotes {

    /**
     * The notes of this track, kept here rather than read from a file. {@code from} is the window
     * they were taken over from, so the editor can say where they came from and read them again.
     */
    record OwnNotes(List<Note> notes, MidiWindow from) implements MelodySource {

        public OwnNotes {
            Objects.requireNonNull(notes, "notes");
            notes = List.copyOf(notes);
        }

        /** The notes a window was showing, taken over as the track's own. */
        public static OwnNotes takenFrom(MidiWindow window, List<Note> notes) {
            return new OwnNotes(notes, window);
        }

        public OwnNotes with(List<Note> next) {
            return new OwnNotes(next, from);
        }
    }
}
