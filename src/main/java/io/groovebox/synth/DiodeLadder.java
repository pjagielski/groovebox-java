package io.groovebox.synth;

/**
 * A four-pole diode ladder, which is the filter a TB-303 has and the two-pole state-variable in
 * {@code NovasawDsp.LowpassFilter} is not. Two things separate them, and both are audible.
 *
 * <p>Four poles rather than two, so it falls away at 24 dB an octave instead of 12; and the
 * resonance can be taken far enough to sing rather than merely to lift. The state-variable's
 * damping is floored in a way that puts its ceiling at Q = 1.58, a bump of four decibels. This one
 * is written so that the feedback can be taken to where the filter oscillates on its own.
 *
 * <p>What makes it a <em>diode</em> ladder rather than a transistor one is that each stage is
 * loaded by the stage after it - the ladder's diodes conduct in both directions, so the stages are
 * coupled rather than being four copies of one filter behind a single feedback path. That coupling
 * is folded into the per-stage gains, computed from the last stage backwards:
 *
 * <pre>
 *   G4 = g / (1 + g)
 *   G3 = g / (1 + g - g*G4/2)   and so on down to G1
 * </pre>
 *
 * <p>which is the form Zavalishin gives in <em>The Art of VA Filter Design</em> §5.10 and Odin 2's
 * {@code DiodeFilter.cpp} computes in the same order. The whole zero-delay loop then resolves in
 * one division, because the chain's output is linear in its input:
 *
 * <pre>
 *   y4 = &#915;*u + &#963;     with &#915; = G1*G2*G3*G4 and &#963; what the four states contribute
 *   u  = x - k*y4      so   y4 = (&#915;*x + &#963;) / (1 + k*&#915;)
 * </pre>
 *
 * <p>The coefficients are recomputed every frame, which is what lets the cutoff be swept by an
 * envelope without the filter being wrong between blocks.
 */
final class DiodeLadder {

    /**
     * The top of the resonance knob. Measured on this filter rather than taken from anywhere: it
     * begins to sing at a feedback of exactly 4.00, at every cutoff tried, so the knob runs a
     * little past that and the last of its travel is where it squeals on its own.
     */
    static final float MAX_FEEDBACK = 4.2f;

    private float s1;
    private float s2;
    private float s3;
    private float s4;

    void reset() {
        s1 = s2 = s3 = s4 = 0;
    }

    /** {@code resonance} is 0 to 1; the top of it is where the filter oscillates by itself. */
    float process(float input, float cutoffHz, float resonance, int sampleRate) {
        return processRaw(input, cutoffHz, clamp(resonance, 0.0f, 1.0f) * MAX_FEEDBACK, sampleRate);
    }

    /** The same with the feedback given as it is, which is how its range was measured. */
    float processRaw(float input, float cutoffHz, float feedback, int sampleRate) {
        float limited = clamp(cutoffHz, 40.0f, sampleRate * 0.45f);
        float g = (float) Math.tan(Math.PI * limited / sampleRate);
        float k = Math.max(0.0f, feedback);

        // from the last stage backwards: each stage's pole is moved by the one loading it
        float g4 = g / (1.0f + g);
        float g3 = g / (1.0f + g - 0.5f * g * g4);
        float g2 = g / (1.0f + g - 0.5f * g * g3);
        float g1 = g / (1.0f + g - 0.5f * g * g2);
        float gamma = g1 * g2 * g3 * g4;

        // what the states alone would put out, carried down the chain to the fourth stage
        float sigma = g4 * g3 * g2 * (1 - g1) * s1
                + g4 * g3 * (1 - g2) * s2
                + g4 * (1 - g3) * s3
                + (1 - g4) * s4;

        float safe = clamp(input, -3.0f, 3.0f);
        float fourth = (gamma * safe + sigma) / (1.0f + k * gamma);
        float first = safe - k * fourth;

        float y1 = g1 * first + (1 - g1) * s1;
        s1 = clamp(2 * y1 - s1, -3.0f, 3.0f);
        float y2 = g2 * y1 + (1 - g2) * s2;
        s2 = clamp(2 * y2 - s2, -3.0f, 3.0f);
        float y3 = g3 * y2 + (1 - g3) * s3;
        s3 = clamp(2 * y3 - s3, -3.0f, 3.0f);
        float y4 = g4 * y3 + (1 - g4) * s4;
        s4 = clamp(2 * y4 - s4, -3.0f, 3.0f);
        return y4;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
