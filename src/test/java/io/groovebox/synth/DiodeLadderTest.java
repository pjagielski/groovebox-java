package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiodeLadderTest {

    private static final int RATE = 48_000;
    private static final float CUTOFF = 1_000;

    /** How loud a sine at {@code hz} comes out, in dB, once the filter has settled. */
    private static double db(float hz, float resonance) {
        DiodeLadder filter = new DiodeLadder();
        int settle = RATE / 8;
        double peak = 0;
        for (int frame = 0; frame < settle * 2; frame++) {
            float out = filter.process((float) Math.sin(2 * Math.PI * hz * frame / RATE),
                    CUTOFF, resonance, RATE);
            if (frame > settle) {
                peak = Math.max(peak, Math.abs(out));
            }
        }
        return 20 * Math.log10(Math.max(1e-9, peak));
    }

    /** The loudest it is still making a second after one impulse, with nothing going in. */
    private static double ringing(float resonance) {
        DiodeLadder filter = new DiodeLadder();
        filter.process(1.0f, CUTOFF, resonance, RATE);
        double loudest = 0;
        for (int frame = 0; frame < RATE; frame++) {
            float out = filter.process(0.0f, CUTOFF, resonance, RATE);
            if (frame > RATE / 2) {
                loudest = Math.max(loudest, Math.abs(out));
            }
        }
        return loudest;
    }

    @Test
    void itHasFourPoles() {
        // three decibels a pole at the cutoff, and four times six an octave above it
        assertEquals(-12.0, db(CUTOFF, 0), 1.0, "at the cutoff");
        // over two octaves, well clear of the bend, where the asymptote actually is
        assertEquals(-48.0, db(8_000, 0) - db(2_000, 0), 2.0, "two octaves of rolloff");
        assertEquals(0.0, db(100, 0), 0.5, "and flat well below it");
    }

    @Test
    void theResonantPeakNarrowsAsItGrows() {
        // what the two-pole cannot do: the peak takes the slope with it rather than only rising
        double gentle = db(683, 0.3f) - db(341, 0.3f);
        double steep = db(937, 0.95f) - db(468, 0.95f);

        assertTrue(gentle < 4, "an octave below a gentle peak is not far down, and was " + gentle);
        assertTrue(steep > 10, "an octave below a strong one is, and was " + steep);
    }

    @Test
    void theBassGoesAsTheResonanceComesUp() {
        // the ladder's own signature: feedback takes the low end with it
        assertEquals(0.0, db(100, 0), 0.5);
        assertTrue(db(100, 0.9f) < -10, "and was " + db(100, 0.9f));
    }

    @Test
    void theTopOfTheKnobIsWhereItSingsOnItsOwn() {
        assertEquals(0.0, ringing(0.5f), 1e-4, "quiet in the middle of the knob");
        assertEquals(0.0, ringing(0.9f), 1e-4, "and still quiet near the top");
        assertTrue(ringing(1.0f) > 0.01, "but singing at the very top, and was " + ringing(1.0f));
    }
}
