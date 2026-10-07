package io.groovebox.session;

import io.groovebox.model.Chord;
import io.groovebox.model.Note;
import io.groovebox.model.Progression;
import io.groovebox.model.Voice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * What a hand on the piano roll does to a track's notes: add one, move it, change its length, take
 * it away. Every one of them gives back a new list, sorted by beat as the engine expects, and none
 * of them lets a note off the loop or off the keyboard.
 *
 * <p>Times are in beats and snap to {@link #SNAP}, a sixteenth at four beats to the bar, so a note
 * dragged by hand still lands where the grid does. What several notes at once become is an
 * {@link Edit}, which carries the notes it made as well as the whole line, so whoever is holding a
 * selection can hold the same notes afterwards.
 */
public final class NoteEdits {

    /** A sixteenth of a bar of four: what a beat is divided into. */
    public static final double SNAP = 0.25;
    public static final double SHORTEST = SNAP;
    /** A note is never turned all the way down: silence is what taking it away is for. */
    public static final float QUIETEST = 0.05f;

    private NoteEdits() {
    }

    /**
     * A line of notes as an edit leaves it, and the notes that edit made: the same ones the hand
     * was holding, in their new shape.
     */
    public record Edit(List<Note> notes, List<Note> touched) {

        public Edit {
            notes = List.copyOf(notes);
            touched = List.copyOf(touched);
        }
    }

    /** {@code beat} at the nearest sixteenth, never before the loop's start. */
    public static double snap(double beat) {
        return Math.max(0, Math.round(beat / SNAP) * SNAP);
    }

    /**
     * A distance in beats at the nearest sixteenth. Unlike {@link #snap(double)}, which is for a
     * place in the loop and so never comes out before its start, this one can be negative: it is
     * how far a hand has dragged, not where it has got to.
     */
    public static double snapStep(double beats) {
        return Math.round(beats / SNAP) * SNAP;
    }

    /** {@code beat} at the sixteenth it falls in: where a click puts a new note. */
    public static double snapDown(double beat) {
        return Math.max(0, Math.floor(beat / SNAP) * SNAP);
    }

    /**
     * A note added at {@code beat} on {@code midiNote}, {@code durationBeats} long, cut short by the
     * end of the loop. A beat at or past the loop's end adds nothing.
     */
    public static List<Note> add(List<Note> notes, double beat, int midiNote, double durationBeats, float velocity,
                          double lengthBeats) {
        double start = snapDown(beat);
        if (start >= lengthBeats || midiNote < 0 || midiNote > 127) {
            return notes;
        }
        double held = Math.max(SHORTEST, Math.min(durationBeats, lengthBeats - start));
        List<Note> next = new ArrayList<>(notes);
        next.add(new Note(start, new Voice.Pitch(midiNote), held, clamp(velocity)));
        return sorted(next);
    }

    /**
     * {@code moving} moved by {@code beats} and {@code semitones}: snapped, kept inside the loop and
     * inside the keyboard, and keeping their lengths. They move as one, so what an edge holds back
     * for any of them holds the rest back with it and a chord keeps its shape.
     */
    public static Edit move(List<Note> notes, Collection<Note> moving, double beats, int semitones, double lengthBeats) {
        if (moving.isEmpty()) {
            return new Edit(notes, List.of());
        }
        int lowest = 127;
        int highest = 0;
        double earliest = Double.MAX_VALUE;
        double latest = 0;
        for (Note note : moving) {
            if (note.voice() instanceof Voice.Pitch pitch) {
                lowest = Math.min(lowest, pitch.midiNote());
                highest = Math.max(highest, pitch.midiNote());
            }
            earliest = Math.min(earliest, note.beat());
            latest = Math.max(latest, note.beat());
        }
        int steps = Math.max(-lowest, Math.min(127 - highest, semitones));
        double furthest = Math.max(0, lengthBeats - SHORTEST) - latest;
        double from = Math.max(-earliest, Math.min(furthest, snapStep(beats)));
        List<Note> next = new ArrayList<>(notes);
        List<Note> moved = new ArrayList<>();
        for (Note note : moving) {
            int index = next.indexOf(note);
            if (index < 0 || !(note.voice() instanceof Voice.Pitch pitch)) {
                continue;
            }
            double start = snap(note.beat() + from);
            double held = Math.max(SHORTEST, Math.min(note.durationBeats(), lengthBeats - start));
            Note shifted = new Note(start, new Voice.Pitch(pitch.midiNote() + steps), held, note.velocity(),
                    note.envelope());
            next.set(index, shifted);
            moved.add(shifted);
        }
        return new Edit(sorted(next), moved);
    }

    /** {@code note} ending at {@code endBeat}: a sixteenth at the shortest, the loop's end at the longest. */
    public static List<Note> resize(List<Note> notes, Note note, double endBeat, double lengthBeats) {
        int index = notes.indexOf(note);
        if (index < 0) {
            return notes;
        }
        double end = Math.min(snap(endBeat), lengthBeats);
        double held = Math.max(SHORTEST, end - note.beat());
        List<Note> next = new ArrayList<>(notes);
        next.set(index, new Note(note.beat(), note.voice(), held, note.velocity(), note.envelope()));
        return next;
    }

    public static List<Note> remove(List<Note> notes, Note note) {
        return remove(notes, List.of(note));
    }

    /** Every one of {@code gone} taken away, one note for each time it is held. */
    public static List<Note> remove(List<Note> notes, Collection<Note> gone) {
        List<Note> next = new ArrayList<>(notes);
        gone.forEach(next::remove);
        return List.copyOf(next);
    }

    /**
     * {@code touched} louder or quieter by {@code delta}, each keeping how it stands against the
     * others: the loudest reaching the top holds the rest back with it, as a move does.
     */
    public static Edit velocity(List<Note> notes, Collection<Note> touched, float delta) {
        if (touched.isEmpty()) {
            return new Edit(notes, List.of());
        }
        float loudest = 0;
        float quietest = 1;
        for (Note note : touched) {
            loudest = Math.max(loudest, note.velocity());
            quietest = Math.min(quietest, note.velocity());
        }
        float step = Math.max(QUIETEST - quietest, Math.min(1.0f - loudest, delta));
        List<Note> next = new ArrayList<>(notes);
        List<Note> changed = new ArrayList<>();
        for (Note note : touched) {
            int index = next.indexOf(note);
            if (index < 0) {
                continue;
            }
            Note louder = new Note(note.beat(), note.voice(), note.durationBeats(), clamp(note.velocity() + step),
                    note.envelope());
            next.set(index, louder);
            changed.add(louder);
        }
        return new Edit(List.copyOf(next), changed);
    }

    /**
     * Each note that its bar's chord holds played as that chord: the note stays where it is, and
     * the chord's other notes are added above it. A note the chord does not hold is left alone, so
     * a passing note stays a passing note rather than becoming a clash, and a bar with no chord
     * keeps its line as it is.
     */
    public static List<Note> chords(List<Note> notes, Progression bars, int beatsPerBar) {
        if (bars.isEmpty()) {
            return notes;
        }
        List<Note> next = new ArrayList<>();
        for (Note note : notes) {
            Chord chord = bars.at(note.beat(), beatsPerBar);
            if (chord == null || !(note.voice() instanceof Voice.Pitch pitch) || !chord.holds(pitch.midiNote())) {
                next.add(note);
                continue;
            }
            for (int sounded : chord.over(pitch.midiNote())) {
                next.add(new Note(note.beat(), new Voice.Pitch(sounded), note.durationBeats(), note.velocity(),
                        note.envelope()));
            }
        }
        return sorted(next);
    }

    /** Every note a band holds: those between two beats and two pitches, edges included. */
    public static List<Note> within(List<Note> notes, double fromBeat, double toBeat, int lowPitch, int highPitch) {
        return notes.stream()
                .filter(note -> note.voice() instanceof Voice.Pitch pitch
                        && pitch.midiNote() >= lowPitch && pitch.midiNote() <= highPitch
                        && note.beat() + note.durationBeats() >= fromBeat && note.beat() <= toBeat)
                .toList();
    }

    /** Every note the loop has room for, the rest left behind: what a shorter loop plays. */
    public static List<Note> within(List<Note> notes, double lengthBeats) {
        return notes.stream()
                .filter(note -> note.beat() < lengthBeats)
                .map(note -> note.beat() + note.durationBeats() <= lengthBeats ? note
                        : new Note(note.beat(), note.voice(), lengthBeats - note.beat(), note.velocity(),
                                note.envelope()))
                .toList();
    }

    private static List<Note> sorted(List<Note> notes) {
        List<Note> next = new ArrayList<>(notes);
        next.sort(Comparator.comparingDouble(Note::beat));
        return List.copyOf(next);
    }

    private static float clamp(float velocity) {
        return Math.max(QUIETEST, Math.min(1.0f, velocity));
    }
}
