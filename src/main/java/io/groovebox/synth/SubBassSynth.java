package io.groovebox.synth;

/**
 * A bass that sits under everything else: barely detuned, so the low end stays one note instead of
 * beating against itself, a closed filter that moves very little, and a sine an octave below
 * carrying the fundamental. Long sustain, short release — a four-to-the-floor bass line under a
 * kick.
 */
public final class SubBassSynth extends NovasawSynth {

    public SubBassSynth() {
        super(
                0.004f, 0.220f, 0.90f, 0.090f,
                1.0f, 3.0f,
                300.0f, 900.0f, 700.0f, 16.0f,
                0.9f, 0.0f,
                0.05f, 0.40f, 0.406f,
                0.30f, 0.30f, 0.10f, 0.70f
        );
    }
}
