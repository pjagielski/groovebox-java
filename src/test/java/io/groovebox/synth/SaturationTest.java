package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaturationTest {

    /** The level the shapes were measured at: what a patch's voices reach by the time they are shaped. */
    private static final float REFERENCE = 0.335f;

    private static double rmsThrough(Saturation shape, float drive) {
        double sum = 0;
        int frames = 40_000;
        for (int frame = 0; frame < frames; frame++) {
            float input = (float) (2.0 * (frame % 401) / 401.0 - 1) * REFERENCE;
            float out = shape.levelled(input, drive);
            sum += out * out;
        }
        return Math.sqrt(sum / frames);
    }

    @Test
    void turningTheDriveUpChangesTheSoundAndNotTheLoudness() {
        double plain = REFERENCE / Math.sqrt(3);
        for (Saturation shape : Saturation.values()) {
            for (double drive = 0; drive <= 2.0001; drive += 0.07) {
                double level = rmsThrough(shape, (float) drive);
                // eight per cent, half a decibel: the table is read every fifth of the knob and
                // the curve between two of its points is steepest at the bottom, where k climbs fastest
                assertEquals(plain, level, plain * 0.08,
                        shape + " at drive " + String.format("%.2f", drive) + " changed the level");
            }
        }
    }

    @Test
    void theDiodeIsLeftExactlyAsItWas() {
        // every patch here is levelled against it, so it must come through this untouched
        for (double drive = 0; drive <= 2.0001; drive += 0.13) {
            for (float input : new float[] {-0.8f, -0.2f, 0.0f, 0.15f, 0.6f}) {
                assertEquals(NovasawDsp.shapeDiodeLevelled(input, (float) drive),
                        Saturation.DIODE.levelled(input, (float) drive), 1e-6f);
            }
        }
    }

    @Test
    void aFoldLeavesWhatIsQuietWhereItWasAndTheDiodeLiftsIt() {
        // what separates the shapes once they are levelled: a fold only turns the peaks round, so
        // everything under them is untouched; the diode bends the whole curve and makes the rest up
        float quiet = 0.001f;
        double fold = Saturation.FOLD.levelled(quiet, 1.0f) / quiet;
        double diode = Saturation.DIODE.levelled(quiet, 1.0f) / quiet;

        // measured: 1.35 against the diode's 2.43 at this drive
        assertTrue(fold < 1.6, "a fold barely lifts a quiet signal, and was " + fold);
        assertTrue(diode > 2.0, "where the diode does, and was " + diode);
        assertTrue(diode > fold * 1.5, "by a good margin over the fold");
    }

    @Test
    void aFoldComesBackDownWhereAClipWouldStayUp() {
        // driven well past the top: the clip is flat, the fold has turned round
        float hard = Saturation.HARD.shape(1.0f, 1.0f);
        float folded = Saturation.FOLD.shape(1.0f, 1.0f);
        assertEquals(1.0f, hard, 1e-6f, "clipped flat");
        assertTrue(folded < 0.6f, "folded back down, but was " + folded);
    }

    @Test
    void everyShapeStaysWithinBounds() {
        for (Saturation shape : Saturation.values()) {
            if (shape == Saturation.DIODE) {
                // it carries a gain of its own inside the curve, and is brought back by levelling
                continue;
            }
            for (float input = -4; input <= 4; input += 0.01f) {
                float out = shape.shape(input, 2.0f);
                // 1.15, not 1: the rational S settles at (1+k)/k rather than at one
                assertTrue(Math.abs(out) <= 1.15f, shape + " reached " + out + " at " + input);
            }
        }
        for (Saturation shape : Saturation.values()) {
            for (float input = -4; input <= 4; input += 0.01f) {
                float out = shape.levelled(input, 2.0f);
                assertTrue(Math.abs(out) <= 1.05f, shape + " levelled reached " + out + " at " + input);
            }
        }
    }
}
