package io.groovebox.synth;

/**
 * The 303 idea: a low cutoff, resonance high enough to whistle around it, and an envelope that
 * slams the filter open and lets it fall shut within the note. Short decay, almost no sustain, and
 * enough drive to make the peak bite.
 *
 * <p>This patch is only playable since the state-variable filter got its missing damping term back;
 * before that, this much resonance ran away instead of singing.
 */
public final class AcidBassSynth extends NovasawSynth {

    public AcidBassSynth() {
        super(
                0.002f, 0.140f, 0.08f, 0.080f,
                2.0f, 6.0f,
                180.0f, 500.0f, 4200.0f, 22.0f,
                1.6f, 0.0f,
                0.55f, 0.90f, 1.024f,
                0.78f, 0.52f, 0.14f, 0.45f
        );
    }
}
