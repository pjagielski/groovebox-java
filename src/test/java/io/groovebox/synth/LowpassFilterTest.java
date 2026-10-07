package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state-variable filter is only stable if the numerator carries both damping terms; dropping
 * the {@code g} one costs nothing at a low cutoff and makes the filter blow up at a high one, which
 * is exactly where a resonant sweep is most tempting.
 */
class LowpassFilterTest {

    private static final int SAMPLE_RATE = 44_100;
    private static final float INPUT_PEAK = 0.2f;

    @Test
    void itStaysBoundedAcrossEveryCutoffAndResonance() {
        for (float cutoff : new float[] {200, 800, 3_000, 8_000, 12_000, 16_000}) {
            for (float resonance : new float[] {0.0f, 0.2f, 0.5f, 0.8f, 0.95f}) {
                var filter = new NovasawDsp.LowpassFilter();
                var noise = new Random(7);
                for (int frame = 0; frame < 20_000; frame++) {
                    float input = (float) (noise.nextDouble() * 2 * INPUT_PEAK - INPUT_PEAK);
                    float output = filter.process(input, cutoff, resonance, SAMPLE_RATE);
                    assertTrue(Math.abs(output) < 1.0f,
                            "cutoff " + cutoff + " resonance " + resonance + " ran away: " + output);
                }
            }
        }
    }

    @Test
    void aResonantPeakGrowsWithResonanceInsteadOfExploding() {
        float quiet = level(8_000, 0.2f);
        float resonant = level(8_000, 0.95f);

        assertTrue(resonant > quiet, "resonance should lift the peak");
        assertTrue(resonant < quiet * 2.5f, "but lift it, not detonate it");
    }

    @Test
    void itPassesWhatIsWellBelowTheCutoffUntouched() {
        var filter = new NovasawDsp.LowpassFilter();
        float output = 0;
        for (int frame = 0; frame < 5_000; frame++) {
            output = filter.process(0.5f, 1_000, 0.5f, SAMPLE_RATE);
        }

        assertEquals(0.5f, output, 1e-3f);
    }

    private static float level(float cutoff, float resonance) {
        var filter = new NovasawDsp.LowpassFilter();
        var noise = new Random(11);
        double energy = 0;
        for (int frame = 0; frame < 20_000; frame++) {
            float input = (float) (noise.nextDouble() * 2 * INPUT_PEAK - INPUT_PEAK);
            float output = filter.process(input, cutoff, resonance, SAMPLE_RATE);
            energy += output * output;
        }
        return (float) Math.sqrt(energy / 20_000);
    }
}
