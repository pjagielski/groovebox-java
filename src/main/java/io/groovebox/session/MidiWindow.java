package io.groovebox.session;

import io.groovebox.model.Note;
import io.groovebox.midi.MidiFileReader;

import javax.sound.midi.Sequence;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a melody track takes its notes from a MIDI file: the file, the track inside it and its
 * exact beat range, counting from 0. An explicit end is exclusive; zero follows the jam's length.
 * A shorter window can repeat to fill the jam, or play just once in each pass.
 */
public record MidiWindow(Path file, int trackIndex, double startBeat, double endBeat, boolean looping)
        implements MelodySource {

    public MidiWindow {
        Objects.requireNonNull(file, "file");
        if (trackIndex < 0) {
            throw new IllegalArgumentException("Track index must not be negative");
        }
        if (!Double.isFinite(startBeat) || startBeat < 0) {
            throw new IllegalArgumentException("Start beat must be finite and not negative");
        }
        if (!Double.isFinite(endBeat) || (endBeat != 0 && endBeat <= startBeat)) {
            throw new IllegalArgumentException("End beat must be finite and after start beat");
        }
    }

    /** A whole-bar range, with its end exclusive, kept compatible with jam configs and bar controls. */
    public MidiWindow(Path file, int trackIndex, int startBar, int endBar, boolean looping) {
        this(file, trackIndex, startBar * (double) Session.BEATS_PER_BAR,
                endBar * (double) Session.BEATS_PER_BAR, looping);
    }

    /** A window as long as the jam, as older configs and new imports begin. */
    public MidiWindow(Path file, int trackIndex, int startBar) {
        this(file, trackIndex, startBar, 0, true);
    }

    public int startBar() {
        return (int) Math.floor(startBeat / Session.BEATS_PER_BAR);
    }

    public int endBar() {
        return (int) Math.ceil(endBeat / Session.BEATS_PER_BAR);
    }

    /** An exact range in beats from the start of the file, so a pickup can fall between bar lines. */
    public MidiWindow withBeatRange(double from, double until) {
        return new MidiWindow(file, trackIndex, from, until, looping);
    }

    /** The chosen excerpt, shifted to beat zero and repeated or cut to the jam's length. */
    public List<Note> notes(Sequence sequence, double lengthBeats) {
        if (endBeat == 0 && startBeat % Session.BEATS_PER_BAR == 0) {
            return Sounds.loadMelodyTrack(sequence, trackIndex, startBar(), lengthBeats / Session.BEATS_PER_BAR).notes();
        }
        double from = startBeat;
        double rangeBeats = endBeat == 0 ? lengthBeats : endBeat - startBeat;
        double until = from + rangeBeats;
        List<Note> phrase = MidiFileReader.readTrack(sequence, trackIndex).stream()
                .filter(note -> note.beat() < until && note.beat() + note.durationBeats() > from)
                // A held note at the first boundary contributes its remaining tail, at beat zero.
                .map(note -> new Note(Math.max(from, note.beat()) - from, note.voice(),
                        Math.min(until, note.beat() + note.durationBeats()) - Math.max(from, note.beat()),
                        note.velocity(), note.envelope()))
                .toList();
        if (phrase.isEmpty()) {
            return List.of();
        }
        List<Note> notes = new ArrayList<>();
        for (double offset = 0; offset < lengthBeats; offset += rangeBeats) {
            for (Note note : phrase) {
                double beat = offset + note.beat();
                if (beat < lengthBeats) {
                    notes.add(new Note(beat, note.voice(), Math.min(note.durationBeats(), lengthBeats - beat),
                            note.velocity(), note.envelope()));
                }
            }
            if (!looping) {
                break;
            }
        }
        return List.copyOf(notes);
    }

    public MidiWindow withFile(Path next) {
        return new MidiWindow(next, trackIndex, startBeat, endBeat, looping);
    }

    public MidiWindow withTrackIndex(int next) {
        return new MidiWindow(file, next, startBeat, endBeat, looping);
    }

    public MidiWindow withStartBar(int next) {
        return new MidiWindow(file, trackIndex, next * (double) Session.BEATS_PER_BAR, endBeat, looping);
    }
}
