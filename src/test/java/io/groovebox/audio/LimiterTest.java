package io.groovebox.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimiterTest {

    private static final int RATE = 44_100;
    private static final float CEILING = 0.99f;

    @Test
    void aMixUnderTheCeilingComesOutAsItWentIn() {
        Limiter limiter = new Limiter(RATE, 2);
        int frames = 1000;
        float[] mix = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            mix[frame * 2] = (float) Math.sin(frame * 0.05) * 0.5f;
            mix[frame * 2 + 1] = (float) Math.sin(frame * 0.05) * 0.5f;
        }
        float[] went = mix.clone();

        limiter.process(mix, frames);

        // the same, a lookahead later: nothing is touched when there is nothing to catch
        int held = limiter.lookaheadFrames();
        for (int frame = held; frame < frames; frame++) {
            assertEquals(went[(frame - held) * 2], mix[frame * 2], 1e-6f, "frame " + frame);
        }
    }

    @Test
    void aPeakIsCaughtBeforeItArrivesRatherThanClipped() {
        Limiter limiter = new Limiter(RATE, 2);
        int frames = 2000;
        float[] mix = new float[frames * 2];
        // silence, then a wall of sound at twice full scale: the worst a transient can be
        for (int frame = 1000; frame < frames; frame++) {
            mix[frame * 2] = 2.0f;
            mix[frame * 2 + 1] = 2.0f;
        }

        limiter.process(mix, frames);

        for (int i = 0; i < frames * 2; i++) {
            assertTrue(Math.abs(mix[i]) <= CEILING + 1e-4f,
                    "nothing over the ceiling, but frame " + i / 2 + " was " + mix[i]);
        }
        // and it is not simply silenced: the wall is there, at the ceiling
        assertEquals(CEILING, mix[(frames - 1) * 2], 1e-3f, "the wall, held at the ceiling");
    }

    @Test
    void theGainComesBackUpAfterAPeakHasPassed() {
        Limiter limiter = new Limiter(RATE, 2);
        int frames = RATE;
        float[] mix = new float[frames * 2];
        // one loud frame, then a quiet tone for a second
        mix[0] = 4.0f;
        mix[1] = 4.0f;
        for (int frame = 100; frame < frames; frame++) {
            mix[frame * 2] = 0.25f;
            mix[frame * 2 + 1] = 0.25f;
        }

        limiter.process(mix, frames);

        assertTrue(mix[200 * 2] < 0.2f, "still held down just after the peak");
        // a seventh of a second of release: most of the way back by half a second, all of it by nine tenths
        assertTrue(mix[(RATE / 2) * 2] > 0.24f, "most of the way back half a second on");
        assertEquals(0.25f, mix[(int) (RATE * 0.9) * 2], 1e-3f, "back to the mix's own level");
    }

    @Test
    void oneGainServesBothChannelsSoTheImageDoesNotMove() {
        Limiter limiter = new Limiter(RATE, 2);
        int frames = 500;
        float[] mix = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            // hard left and far too loud; the right is quiet and must come down with it
            mix[frame * 2] = 4.0f;
            mix[frame * 2 + 1] = 0.4f;
        }

        limiter.process(mix, frames);

        int last = frames - 1;
        assertEquals(CEILING, mix[last * 2], 1e-3f, "the loud side, held at the ceiling");
        assertEquals(0.1f, mix[last * 2 + 1] / mix[last * 2], 1e-3f, "and the quiet side still a tenth of it");
    }
}
