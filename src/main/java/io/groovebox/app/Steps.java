package io.groovebox.app;

import io.groovebox.model.Note;
import io.groovebox.model.Voice;
import io.groovebox.session.GridRow;
import io.groovebox.session.Session;

import java.util.List;

/**
 * Where the sixteen pads fall on what a track holds. A drum row is one bar cut into however many
 * steps it has, so a row of more than sixteen is shown a page of sixteen at a time; a melody is
 * shown a bar at a time, a sixteenth to a pad.
 */
final class Steps {
    static final int PADS = 16;
    static final double PAD_BEATS = (double) Session.BEATS_PER_BAR / PADS;
    private static final String[] NAMES = {"c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"};

    private Steps() {
    }

    /** The step of {@code row} sounding at {@code beat}; -1 while nothing plays. */
    static int current(GridRow row, double beat) {
        if (beat < 0) {
            return -1;
        }
        double inBar = beat % Session.BEATS_PER_BAR;
        return (int) Math.floor(inBar / Session.BEATS_PER_BAR * row.accents().length);
    }

    /** The page of sixteen the playhead is on, or the first one while nothing plays. */
    static int page(GridRow row, double beat) {
        int current = current(row, beat);
        return current < 0 ? 0 : current / PADS;
    }

    /** The bar of the loop the playhead is in, or the first while nothing plays. */
    static int bar(double beat) {
        return beat < 0 ? 0 : (int) Math.floor(beat / Session.BEATS_PER_BAR);
    }

    /** The sixteenth of the bar the playhead is on; -1 while nothing plays. */
    static int sixteenth(double beat) {
        return beat < 0 ? -1 : (int) Math.floor((beat % Session.BEATS_PER_BAR) / PAD_BEATS);
    }

    /** What each pad of {@code bar} shows of a melody: the note that starts there, a hold, or nothing. */
    static Pads.Pad[] melody(List<Note> notes, int bar) {
        Pads.Pad[] pads = new Pads.Pad[PADS];
        double from = bar * (double) Session.BEATS_PER_BAR;
        for (int pad = 0; pad < PADS; pad++) {
            double start = from + pad * PAD_BEATS;
            double end = start + PAD_BEATS;
            Note starting = null;
            boolean held = false;
            for (Note note : notes) {
                if (note.beat() >= start - 1e-9 && note.beat() < end - 1e-9) {
                    if (starting == null || pitchOf(note) < pitchOf(starting)) {
                        starting = note;
                    }
                } else if (note.beat() < start && note.beat() + note.durationBeats() > start + 1e-9) {
                    held = true;
                }
            }
            pads[pad] = starting != null ? Pads.Pad.hit(nameOf(pitchOf(starting)), starting.velocity())
                    : held ? Pads.Pad.HELD : Pads.Pad.EMPTY;
        }
        return pads;
    }

    /**
     * The step of a loop of {@code count} steps a pass of {@code passBeats} has reached at
     * {@code beat}; -1 while nothing plays. A pass starts again every {@code passBeats}.
     */
    static int loopStep(double beat, double passBeats, int count) {
        if (beat < 0 || count <= 0) {
            return -1;
        }
        return Math.min(count - 1, (int) Math.floor(beat % passBeats / passBeats * count));
    }

    /**
     * What each pad of {@code page} shows of a cut loop: the number of the slice its step plays,
     * counted from 1 as they are said, stronger where it is not the step's own slice; a dashed pad
     * for a step that strikes nothing; nothing past the last step.
     */
    static Pads.Pad[] loop(List<Integer> order, int page) {
        Pads.Pad[] pads = new Pads.Pad[PADS];
        for (int pad = 0; pad < PADS; pad++) {
            int step = page * PADS + pad;
            if (step >= order.size()) {
                pads[pad] = Pads.Pad.OFF;
            } else if (order.get(step) < 0) {
                pads[pad] = Pads.Pad.REST;
            } else {
                int slice = order.get(step);
                pads[pad] = Pads.Pad.hit(String.valueOf(slice + 1), slice == step ? 0.8f : 1.0f);
            }
        }
        return pads;
    }

    /** How many pages of sixteen it takes to hold {@code steps}, never none. */
    static int pages(int steps) {
        return Math.max(1, (steps + PADS - 1) / PADS);
    }

    static int pitchOf(Note note) {
        return note.voice() instanceof Voice.Pitch pitch ? pitch.midiNote() : -1;
    }

    /** 40 -> "e2": as the notes are said, in the octave numbering MIDI uses. */
    static String nameOf(int midiNote) {
        return NAMES[Math.floorMod(midiNote, 12)] + (Math.floorDiv(midiNote, 12) - 1);
    }
}
