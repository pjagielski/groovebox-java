package io.groovebox.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A chord as a name gives it: a root and the notes stacked on it. It is what the studio marks the
 * roll's rows with, bar by bar, and what turns one note of a line into several.
 *
 * <p>Written the way anybody writes them — {@code C}, {@code Cm}, {@code F#m7}, {@code Absus4} —
 * and read back the same way, in sharps, since the roll's black keys are named that way.
 */
public record Chord(int root, Quality quality) {

    /** What is stacked on the root, in semitones. */
    public enum Quality {
        MAJOR("", 0, 4, 7),
        MINOR("m", 0, 3, 7),
        DIMINISHED("dim", 0, 3, 6),
        AUGMENTED("aug", 0, 4, 8),
        MAJOR_SEVENTH("maj7", 0, 4, 7, 11),
        MINOR_SEVENTH("m7", 0, 3, 7, 10),
        DOMINANT_SEVENTH("7", 0, 4, 7, 10),
        SUS_FOURTH("sus4", 0, 5, 7),
        SUS_SECOND("sus2", 0, 2, 7);

        private final String suffix;
        private final int[] steps;

        Quality(String suffix, int... steps) {
            this.suffix = suffix;
            this.steps = steps;
        }

        public String suffix() {
            return suffix;
        }

        int[] steps() {
            return steps;
        }
    }

    /** The suffixes, longest first, so "maj7" is read before "m" swallows its first letter. */
    private static final List<Quality> READ_IN_ORDER = List.of(Quality.MAJOR_SEVENTH, Quality.MINOR_SEVENTH,
            Quality.SUS_FOURTH, Quality.SUS_SECOND, Quality.DIMINISHED, Quality.AUGMENTED,
            Quality.DOMINANT_SEVENTH, Quality.MINOR, Quality.MAJOR);

    public Chord {
        if (root < 0 || root > 11) {
            throw new IllegalArgumentException("A root is one of the twelve notes, not " + root);
        }
    }

    /**
     * The chord {@code text} names — {@code Eb}, {@code F#m7} — or null when it names none. Flats
     * are read and given back as the sharp beside them.
     */
    public static Chord parse(String text) {
        String name = text == null ? "" : text.trim();
        if (name.isEmpty()) {
            return null;
        }
        int step = "CDEFGAB".indexOf(Character.toUpperCase(name.charAt(0)));
        if (step < 0) {
            return null;
        }
        int root = new int[] {0, 2, 4, 5, 7, 9, 11}[step];
        String rest = name.substring(1);
        if (rest.startsWith("#")) {
            root = (root + 1) % 12;
            rest = rest.substring(1);
        } else if (rest.startsWith("b") && !rest.toLowerCase(Locale.ROOT).startsWith("bm")) {
            // a flat, unless the letter is the start of a chord's own name (Bb against Bm)
            root = Math.floorMod(root - 1, 12);
            rest = rest.substring(1);
        }
        for (Quality quality : READ_IN_ORDER) {
            if (rest.equalsIgnoreCase(quality.suffix())) {
                return new Chord(root, quality);
            }
        }
        return null;
    }

    /** Whether this chord holds {@code midiNote}, whichever octave it is played in. */
    public boolean holds(int midiNote) {
        for (int step : quality.steps()) {
            if (Math.floorMod(midiNote - root - step, 12) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The chord over {@code midiNote}: the note itself, and every other note of the chord at the
     * first pitch above it, so what was written stays the lowest of them and the chord sits within
     * an octave of it.
     */
    public List<Integer> over(int midiNote) {
        List<Integer> notes = new ArrayList<>();
        notes.add(midiNote);
        for (int step : quality.steps()) {
            int above = midiNote + Math.floorMod(root + step - midiNote, 12);
            if (above != midiNote && above <= 127) {
                notes.add(above);
            }
        }
        // low to high, so the note written stays the first of them and the chord reads upwards
        notes.sort(Integer::compare);
        return List.copyOf(notes);
    }

    public String name() {
        return Scale.ROOTS.get(root) + quality.suffix();
    }

    @Override
    public String toString() {
        return name();
    }
}
