package io.groovebox.synth;

/** What a delay repeat is worth in beats, so the echo lands with the jam instead of near it. */
public enum DelaySync {
    FREE("Free", 0), QUARTER("1/4", 1.0), DOTTED_EIGHTH("1/8.", 0.75),
    EIGHTH("1/8", 0.5), TRIPLET_EIGHTH("1/8T", 1.0 / 3), SIXTEENTH("1/16", 0.25);

    private final String label;
    private final double beats;

    DelaySync(String label, double beats) {
        this.label = label;
        this.beats = beats;
    }

    /** The division a track keeps by name, or {@link #FREE} for a name that is none of them. */
    public static DelaySync named(String name) {
        for (DelaySync division : values()) {
            if (division.name().equals(name)) {
                return division;
            }
        }
        return FREE;
    }

    /** How long one repeat lasts at {@code bpm}, in milliseconds. */
    public double millis(double bpm) {
        return beats * 60_000 / bpm;
    }

    /**
     * {@code effects} with the delay locked to this division at {@code bpm}; a free delay, or a
     * tempo of nothing, keeps the time it has.
     */
    public EffectParams timed(EffectParams effects, double bpm) {
        return this == FREE || bpm <= 0 ? effects : effects.withDelayMillis((float) millis(bpm));
    }

    @Override
    public String toString() {
        return label;
    }
}
