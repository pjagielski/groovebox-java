package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PitchRatioTest {
    @Test
    void driftApproximationStaysWithinOneMicrocentOverTheSupportedRange() {
        double worstCents = 0;
        for (int i = -550; i <= 550; i++) {
            double cents = i / 1000.0;
            double exact = Math.pow(2, cents / 1200.0);
            double approximated = NovasawVoice.driftRatio(cents);
            worstCents = Math.max(worstCents,
                    Math.abs(1200 * Math.log(approximated / exact) / Math.log(2)));
        }
        assertTrue(worstCents < 0.000001, "worst pitch error: " + worstCents + " cents");
    }
}
