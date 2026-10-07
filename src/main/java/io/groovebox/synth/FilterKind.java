package io.groovebox.synth;

/**
 * Which filter a patch is played through. The two are not the same filter at different settings:
 * one has two poles and the other four, and the four-pole one can be taken to where it sings.
 *
 * <p>{@link #TWO_POLE} is what every patch here was written for and stays the default. Its damping
 * is floored, which puts its most resonant setting at Q = 1.58 - a bump of four decibels, of a
 * fixed shape. {@link #LADDER} is the TB-303's arrangement: four poles, and a peak that narrows as
 * it grows and takes the low end away with it, up to a feedback where it oscillates by itself.
 */
public enum FilterKind {

    /** Two poles, twelve decibels an octave: the state-variable filter the patches were tuned on. */
    TWO_POLE("2-pole"),
    /** Four poles, twenty-four an octave, and resonance far enough to sing. */
    LADDER("Ladder");

    private final String label;

    FilterKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
