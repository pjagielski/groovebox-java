package io.groovebox.model;

import java.util.List;

/**
 * The key a jam is in: a root note and the steps a mode takes from it. It forbids nothing — it is
 * what the studio shades the piano roll with, so the notes that belong are the ones that stay
 * white and a note outside the key looks like the choice it is.
 *
 * <p>{@link Mode#ANY} holds every note, which is what a jam is in until someone says otherwise.
 * {@link #guess} reads a key off a line of notes, which is how a jam that comes from a MIDI file
 * gets one without anybody naming it.
 */
public record Scale(int root, Mode mode) {

    /** The steps of a mode from its root, in semitones. */
    public enum Mode {
        ANY("Any", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        MAJOR("major", 0, 2, 4, 5, 7, 9, 11),
        MINOR("minor", 0, 2, 3, 5, 7, 8, 10),
        HARMONIC_MINOR("harmonic minor", 0, 2, 3, 5, 7, 8, 11),
        DORIAN("dorian", 0, 2, 3, 5, 7, 9, 10),
        PHRYGIAN("phrygian", 0, 1, 3, 5, 7, 8, 10),
        LYDIAN("lydian", 0, 2, 4, 6, 7, 9, 11),
        MIXOLYDIAN("mixolydian", 0, 2, 4, 5, 7, 9, 10),
        MINOR_PENTATONIC("minor pentatonic", 0, 3, 5, 7, 10),
        MAJOR_PENTATONIC("major pentatonic", 0, 2, 4, 7, 9);

        private final String label;
        private final boolean[] steps = new boolean[12];

        Mode(String label, int... semitones) {
            this.label = label;
            for (int step : semitones) {
                steps[step] = true;
            }
        }

        boolean holdsStep(int semitones) {
            return steps[semitones];
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The twelve roots, as a picker offers them; sharps rather than flats, as the roll's keys are drawn. */
    public static final List<String> ROOTS =
            List.of("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B");

    /** No key chosen: every note belongs, and the roll shades nothing. */
    public static final Scale ANY = new Scale(0, Mode.ANY);

    /**
     * The modes {@link #guess} chooses between, in the order a tie is broken by: the two anybody
     * would name first, then the rest of the modes.
     */
    private static final List<Mode> GUESSED = List.of(Mode.MINOR, Mode.MAJOR, Mode.DORIAN, Mode.MIXOLYDIAN,
            Mode.PHRYGIAN, Mode.LYDIAN, Mode.HARMONIC_MINOR);

    public Scale {
        if (root < 0 || root > 11) {
            throw new IllegalArgumentException("A root is one of the twelve notes, not " + root);
        }
    }

    /** Whether {@code midiNote} belongs to this key, whichever octave it is in. */
    public boolean holds(int midiNote) {
        return mode.holdsStep(Math.floorMod(midiNote - root, 12));
    }

    /** Whether this note is the key's own root, the one a line comes home to. */
    public boolean isRoot(int midiNote) {
        return mode != Mode.ANY && Math.floorMod(midiNote - root, 12) == 0;
    }

    /** Whether anything is shaded at all: a jam with no key chosen holds every note. */
    public boolean chosen() {
        return mode != Mode.ANY;
    }

    /**
     * The key a line of notes is most likely in: the one that holds the most of what is played,
     * weighed by how long each note is held and how hard it is struck, so a root held for a bar
     * counts for more than a sixteenth passing through.
     *
     * <p>Two keys can hold exactly the same notes — C major and A minor do — and then the one
     * whose root is played the most wins, which is what tells them apart by ear as well. Only the
     * seven-note modes are guessed at: a pentatonic is a subset of one, so it would fit any line
     * its parent scale fits and shade away notes the hand may well want. Nothing to go on gives
     * {@link #ANY}.
     */
    public static Scale guess(List<Note> notes) {
        double[] weight = new double[12];
        double total = 0;
        for (Note note : notes) {
            if (note.voice() instanceof Voice.Pitch pitch) {
                double heard = Math.max(0.05, note.durationBeats()) * Math.max(0.05f, note.velocity());
                weight[Math.floorMod(pitch.midiNote(), 12)] += heard;
                total += heard;
            }
        }
        if (total == 0) {
            return ANY;
        }
        Scale best = ANY;
        double bestHeld = -1;
        double bestRoot = -1;
        for (Mode mode : GUESSED) {
            for (int root = 0; root < 12; root++) {
                Scale candidate = new Scale(root, mode);
                double held = 0;
                for (int step = 0; step < 12; step++) {
                    if (candidate.holds(step)) {
                        held += weight[step];
                    }
                }
                // a hair's allowance: two keys holding the same notes are told apart by their roots
                if (held > bestHeld + 1e-9 || (held > bestHeld - 1e-9 && weight[root] > bestRoot + 1e-9)) {
                    best = candidate;
                    bestHeld = held;
                    bestRoot = weight[root];
                }
            }
        }
        return best;
    }

    public String rootName() {
        return ROOTS.get(root);
    }

    @Override
    public String toString() {
        return mode == Mode.ANY ? mode.toString() : rootName() + " " + mode;
    }
}
