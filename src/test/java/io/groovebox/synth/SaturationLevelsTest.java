package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps the levels a shape is measured at honest. The numbers in {@link Saturation} were taken by
 * driving this saw through each shape; if a curve is ever changed and its numbers are not taken
 * again, this is what says so.
 */
class SaturationLevelsTest {

    /**
     * The level the shapes are levelled at. Found by scanning for the one at which measuring the
     * old diode reproduces the fit it was given long before any of the other shapes existed, so
     * every shape is levelled against the same thing rather than each against itself.
     */
    private static final float REFERENCE = 0.335f;

    private static final int FRAMES = 44_100;

    private static double gainThrough(Saturation shape, float drive) {
        double in = 0;
        double out = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            float input = (float) (2.0 * (frame % 401) / 401.0 - 1) * REFERENCE;
            float shaped = shape.shape(input, drive);
            in += input * input;
            out += shaped * shaped;
        }
        return Math.sqrt(out / in);
    }

    @Test
    void theLevelTheShapesAreMeasuredAtIsStillTheOneTheOldFitWasMadeAt() {
        // within a tenth: the old fit was a curve drawn through measurements, never exact, and it is
        // kept as it is because every patch is levelled against it - see Saturation.gain
        for (double drive = 0; drive <= 2.0001; drive += 0.2) {
            assertEquals(NovasawDsp.driveGain((float) drive), gainThrough(Saturation.DIODE, (float) drive),
                    NovasawDsp.driveGain((float) drive) * 0.1,
                    "the diode measured at " + REFERENCE + " should come out near its fitted curve, at drive " + drive);
        }
    }

    @Test
    void everyShapeStillAddsTheLevelItsNumbersSayItDoes() {
        for (Saturation shape : Saturation.values()) {
            if (shape == Saturation.DIODE) {
                // it answers with its old fit rather than with what it measures, on purpose
                continue;
            }
            for (int step = 0; step <= 10; step++) {
                float drive = step * 0.2f;
                assertEquals(gainThrough(shape, drive), shape.gain(drive), shape.gain(drive) * 0.02,
                        shape + " at drive " + drive + " no longer adds what its numbers say");
            }
        }
    }
}
