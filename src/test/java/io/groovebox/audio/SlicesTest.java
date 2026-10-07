package io.groovebox.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlicesTest {

    /** Silence with a hit at each of {@code at}: a burst of a few hundred frames, loud. */
    private static Sample withHitsAt(int frames, int... at) {
        float[] audio = new float[frames];
        for (int hit : at) {
            for (int frame = hit; frame < hit + 400 && frame < frames; frame++) {
                audio[frame] = 0.9f - 0.9f * (frame - hit) / 400f;
            }
        }
        return Sample.mono(audio);
    }

    @Test
    void theGridCutsAPassIntoEqualSlicesFromItsTop() {
        assertArrayEquals(new int[] {0, 1_000, 2_000, 3_000}, Slices.onTheGrid(4_000, 4));
        assertArrayEquals(new int[] {0}, Slices.onTheGrid(4_000, 1));
    }

    @Test
    void aLoopIsCutIntoAtLeastOneSlice() {
        assertThrows(IllegalArgumentException.class, () -> Slices.onTheGrid(4_000, 0));
    }

    @Test
    void aBoundaryMovesToTheHitItWasNearlyOn() {
        // hits a little after each quarter: 0, and then 120, 100 and 140 frames late
        Sample audio = withHitsAt(16_000, 0, 4_120, 8_100, 12_140);

        int[] starts = Slices.onTheHits(audio, 4, 0.25);

        assertEquals(0, starts[0], "the top of the pass never moves");
        for (int slice = 1; slice < 4; slice++) {
            int hit = new int[] {0, 4_120, 8_100, 12_140}[slice];
            assertTrue(Math.abs(starts[slice] - hit) <= 256,
                    "slice " + slice + " landed at " + starts[slice] + ", not near " + hit);
        }
    }

    @Test
    void aBoundaryWithNothingNearItStaysOnTheGrid() {
        // one hit, at the top; the rest of the loop is silence
        Sample audio = withHitsAt(16_000, 0);

        int[] starts = Slices.onTheHits(audio, 4, 0.25);

        assertArrayEquals(new int[] {0, 4_000, 8_000, 12_000}, starts);
    }

    @Test
    void aBoundaryDoesNotReachIntoTheSliceAfterItForAHit() {
        // the only hit is halfway through the second slice, which is nobody's boundary
        Sample audio = withHitsAt(16_000, 6_000);

        int[] starts = Slices.onTheHits(audio, 4, 0.25);

        // a quarter of a slice is 1000 frames, so 6000 is out of reach of 4000 and of 8000 alike
        assertEquals(4_000, starts[1]);
        assertEquals(8_000, starts[2]);
    }
}
