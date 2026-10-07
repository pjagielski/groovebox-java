package io.groovebox.synth;

/**
 * Which filter a channel is heard through, if any. The two are the same pair a voice can be given —
 * see {@link FilterKind} — with the difference that a channel can be given neither.
 *
 * <p>Neither is the default, and it is a real bypass rather than a filter wide open: no filter here
 * is transparent, and a synth already filters its own voices. A channel that added another one
 * quietly would change every patch in the studio by however much an open filter colours what goes
 * through it.
 */
public enum ChannelFilter {

    /** The channel passes untouched, which is where a synth leaves it. */
    OFF("Off"),
    /** Twelve decibels an octave, the state-variable filter a voice has. */
    TWO_POLE("2-pole"),
    /** Twenty-four an octave, the diode ladder: darker, and it will oscillate at the top of its Q. */
    LADDER("Ladder");

    private final String label;

    ChannelFilter(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
