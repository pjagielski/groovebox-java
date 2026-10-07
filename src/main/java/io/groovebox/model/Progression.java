package io.groovebox.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The chords a jam goes round: one per bar, starting again when the loop runs past the last of
 * them, so "Cm Ab" over eight bars is four times round. It is what the roll marks its rows by and
 * what turns a line into chords.
 *
 * <p>Written as the chords are said: {@code Cm Ab Eb Bb}. A bar with no chord is written
 * {@code -}, and holds whatever the roll would show without any chords at all.
 */
public record Progression(List<Chord> bars) {

    /** No chords named: the roll marks nothing. */
    public static final Progression NONE = new Progression(List.of());

    public Progression {
        // not List.copyOf: a bar with no chord is a null, and that is what it stays
        bars = Collections.unmodifiableList(new ArrayList<>(bars));
    }

    /**
     * The chords {@code text} names, or {@link #NONE} when it names none. A word that is not a
     * chord makes the whole line nothing, so a half-typed name does not change what is shown until
     * it is a chord.
     */
    public static Progression parse(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        List<Chord> bars = new ArrayList<>();
        for (String word : text.trim().split("[\\s,|]+")) {
            if (word.equals("-")) {
                bars.add(null);
                continue;
            }
            Chord chord = Chord.parse(word);
            if (chord == null) {
                return NONE;
            }
            bars.add(chord);
        }
        return new Progression(bars);
    }

    /** The chord of the bar {@code beat} falls in, or null where the bar has none. */
    public Chord at(double beat, int beatsPerBar) {
        if (bars.isEmpty() || beat < 0) {
            return null;
        }
        int bar = (int) Math.floor(beat / beatsPerBar);
        return bars.get(Math.floorMod(bar, bars.size()));
    }

    public boolean isEmpty() {
        return bars.isEmpty();
    }

    @Override
    public String toString() {
        return bars.stream().map(chord -> chord == null ? "-" : chord.name()).reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}
