package io.groovebox.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceFxTest {
    private static final int RATE = 44_100;
    private static final double BPM = 120;

    @Test
    void withNothingHeldTheMixPassesUntouched() {
        var fx = new PerformanceFx(RATE);
        float[] mix = sine(1_000, RATE / 10);
        float[] before = mix.clone();

        fx.process(mix, RATE / 10, BPM);

        assertArrayEquals(before, mix);
    }

    @Test
    void theFilterAtTheBottomTakesTheTopOffAndAtTheTopTheBottom() {
        assertTrue(filtered(0.0, 5_000) < 0.02, "low-pass shut: a 5 kHz tone is all but gone");
        assertTrue(filtered(0.0, 60) > 0.6, "and a 60 Hz one comes through");
        assertTrue(filtered(1.0, 100) < 0.02, "high-pass open: a 100 Hz tone is all but gone");
        assertTrue(filtered(1.0, 15_000) > 0.6, "and a 15 kHz one comes through");
        double centre = filtered(0.5, 1_000);
        assertTrue(centre > 0.95 && centre < 1.05, "at the centre the filter is not heard: " + centre);
    }

    @Test
    void crushHoldsEachSampleForAWhileAndRoundsIt() {
        var fx = new PerformanceFx(RATE);
        fx.crush(1.0);
        float[] mix = sine(440, RATE / 10);

        fx.process(mix, RATE / 10, BPM);

        // at the top: about 700 samples a second, so each value is held for some 63 frames
        int from = RATE / 20;
        int changes = 0;
        for (int frame = from + 1; frame < RATE / 10; frame++) {
            if (mix[frame * 2] != mix[(frame - 1) * 2]) {
                changes++;
            }
        }
        assertEquals(35, changes, 2);
    }

    @Test
    void lettingGoFadesTheEffectOutAndThenLeavesTheMixAlone() {
        var fx = new PerformanceFx(RATE);
        fx.crush(1.0);
        fx.process(sine(440, 4_410), 4_410, BPM);
        fx.releaseAll();

        fx.process(sine(440, 441), 441, BPM);
        float[] mix = sine(440, 4_410);
        float[] before = mix.clone();
        fx.process(mix, 4_410, BPM);

        assertArrayEquals(before, mix, "5 ms after letting go, nothing is left of it");
    }

    @Test
    void theTalkboxPutsEachVowelsFormantsIntoTheMix() {
        // 650 Hz is the "a"'s first formant, 1870 Hz the "i"'s second
        assertTrue(talked(0.0, 650) > 2 * talked(0.5, 650),
                "an \"a\" opens up around 650 Hz: " + talked(0.0, 650) + " against " + talked(0.5, 650));
        assertTrue(talked(0.5, 1_870) > 2 * talked(0.0, 1_870),
                "an \"i\" around 1870 Hz: " + talked(0.5, 1_870) + " against " + talked(0.0, 1_870));
        // a whole mix loses a good deal through three bands, but should not drop out of the jam
        var fx = new PerformanceFx(RATE);
        fx.talkbox(0.0);
        float[] noise = noise(RATE / 2);
        double dry = rms(noise, RATE / 4, RATE / 2);
        fx.process(noise, RATE / 2, BPM);
        double kept = rms(noise, RATE / 4, RATE / 2) / dry;
        assertTrue(kept > 0.4 && kept < 1.2, "and it stays within a few dB of the mix: " + kept);
    }

    @Test
    void dirtyOverdrivesTheMixWithoutRunningAwayWithItsLevel() {
        float[] mix = sine(220, RATE / 10);
        double clean = rms(mix, 0, RATE / 10);
        var fx = new PerformanceFx(RATE);
        fx.dirty(1.0);

        fx.process(mix, RATE / 10, BPM);

        double dirty = rms(mix, RATE / 20, RATE / 10);
        assertTrue(dirty > 0.7 * clean && dirty < 1.6 * clean, "about the level it came in at: " + dirty);
        // a sine driven into a shaper comes out with harmonics on it: it is no longer a sine
        double third = tone(mix, 660) / tone(mix, 220);
        assertTrue(third > 0.05, "and with a third harmonic on it: " + third);
    }

    @Test
    void dubRepeatsADottedEighthLaterAndRingsOnAfterLettingGo() {
        var fx = new PerformanceFx(RATE);
        fx.dub(1.0);
        // a click, once the effect has faded in, and a dotted eighth at 120 BPM is 0.375 s
        float[] mix = new float[RATE * 2];
        int click = RATE / 10;
        mix[click * 2] = 1.0f;
        mix[click * 2 + 1] = 1.0f;

        fx.process(mix, RATE, BPM);

        int firstRepeat = loudestFrame(mix, click + 100, RATE);
        assertEquals(0.375, (firstRepeat - click) / (double) RATE, 0.005, "the repeat, a dotted eighth on");

        fx.dub(-1);
        float[] after = new float[RATE * 2];
        fx.process(after, RATE, BPM);

        float loudest = Math.abs(after[loudestFrame(after, 0, RATE) * 2]);
        assertTrue(loudest > 0.01, "and the line rings on after the finger is off: " + loudest);
    }

    /** How loud {@code hz} is in {@code mix}: one bin of a Fourier transform, by hand. */
    private static double tone(float[] mix, double hz) {
        double real = 0;
        double imaginary = 0;
        int frames = mix.length / 2;
        for (int frame = 0; frame < frames; frame++) {
            double angle = 2 * Math.PI * hz * frame / RATE;
            real += mix[frame * 2] * Math.cos(angle);
            imaginary += mix[frame * 2] * Math.sin(angle);
        }
        return Math.hypot(real, imaginary) / frames;
    }

    private static int loudestFrame(float[] mix, int from, int to) {
        int loudest = from;
        for (int frame = from; frame < to; frame++) {
            if (Math.abs(mix[frame * 2]) > Math.abs(mix[loudest * 2])) {
                loudest = frame;
            }
        }
        return loudest;
    }

    private static double filtered(double position, double hz) {
        return level(fx -> fx.filter(position), hz);
    }

    private static double talked(double position, double hz) {
        return level(fx -> fx.talkbox(position), hz);
    }

    /** The level of a {@code hz} tone through the effect {@code hold} holds, against the tone's own. */
    private static double level(java.util.function.Consumer<PerformanceFx> hold, double hz) {
        var fx = new PerformanceFx(RATE);
        hold.accept(fx);
        int frames = RATE / 2;
        float[] mix = sine(hz, frames);
        double dry = rms(mix, frames / 2, frames);
        fx.process(mix, frames, BPM);
        return rms(mix, frames / 2, frames) / dry;
    }

    private static float[] sine(double hz, int frames) {
        float[] mix = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (0.5 * Math.sin(2 * Math.PI * hz * frame / RATE));
            mix[frame * 2] = value;
            mix[frame * 2 + 1] = value;
        }
        return mix;
    }

    /** A steady, repeatable hiss: the broad spectrum a mix has, without a jam to render. */
    private static float[] noise(int frames) {
        var random = new java.util.Random(42);
        float[] mix = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (random.nextGaussian() * 0.2);
            mix[frame * 2] = value;
            mix[frame * 2 + 1] = value;
        }
        return mix;
    }

    private static double rms(float[] mix, int from, int to) {
        double sum = 0;
        for (int frame = from; frame < to; frame++) {
            sum += mix[frame * 2] * mix[frame * 2];
        }
        return Math.sqrt(sum / (to - from));
    }
}
