package io.groovebox.synth;

/**
 * Held chords under a four-to-the-floor kick: the sound the sidechain was made for. It speaks at
 * once (20 ms) so each chord lands with the bar, holds nearly full, and lets go in a third of a
 * second so one chord does not blur into the next. Seven saws spread wide, a little sub for body,
 * the filter open enough to be bright but short of the lead's edge, and a slow shimmer of
 * vibrato. Duck it under the kick and it pumps.
 */
public final class ChordsSynth extends NovasawSynth {

    public ChordsSynth() {
        super(
                0.020f, 0.800f, 0.85f, 0.350f,
                8.0f, 22.0f,
                900.0f, 4700.0f, 1030.0f, 20.0f,
                0.30f, 7.0f,
                0.30f, 0.70f, 0.522f,
                0.50f, 0.40f, 0.30f,
                0.25f
        );
    }
}
