package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveformTest {

    private static final int RATE = 48_000;

    /** How much energy sits at {@code hz}, by Goertzel. */
    private static double energyAt(float[] wave, double hz) {
        double coefficient = 2 * Math.cos(2 * Math.PI * hz / RATE);
        double s1 = 0;
        double s2 = 0;
        for (float sample : wave) {
            double s0 = sample + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return Math.sqrt(Math.max(0, s1 * s1 + s2 * s2 - coefficient * s1 * s2)) / wave.length;
    }

    private static float[] square(double hz, boolean corrected) {
        float[] out = new float[RATE];
        double phase = 0;
        float increment = (float) (hz / RATE);
        for (int frame = 0; frame < out.length; frame++) {
            out[frame] = corrected ? NovasawDsp.polyBlepSquare((float) phase, increment)
                    : (phase < 0.5 ? 1.0f : -1.0f);
            phase += increment;
            if (phase >= 1.0) {
                phase -= 1.0;
            }
        }
        return out;
    }

    private static double voiceLevel(SynthParams params) {
        var held = new AtomicReference<>(params);
        int frames = RATE / 3;
        NovasawVoice voice = new NovasawVoice(40, frames, RATE, held::get);
        double sum = 0;
        int counted = 0;
        for (int frame = 0; frame < frames; frame++) {
            float out = voice.next();
            if (frame > RATE / 10) {
                sum += out * out;
                counted++;
            }
        }
        return Math.sqrt(sum / counted);
    }

    private static float[] heldVoice(SynthParams params, int note) {
        NovasawVoice voice = new NovasawVoice(note, RATE, RATE, () -> params);
        for (int frame = 0; frame < RATE / 10; frame++) {
            voice.next();
        }
        float[] out = new float[RATE * 2 / 5];
        for (int frame = 0; frame < out.length; frame++) {
            out[frame] = voice.next();
        }
        return out;
    }

    @Test
    void theSquareIsBandLimited() {
        // a square at 5 kHz has its ninth harmonic at 45 kHz, which folds back to 3 kHz; nothing
        // the square is meant to contain lands there, so whatever is there is the fold
        double corrected = energyAt(square(5_000, true), 3_000);
        double naive = energyAt(square(5_000, false), 3_000);

        assertTrue(corrected < naive / 30, "the correction should take the fold down by 30 dB and more, but "
                + "took it from " + naive + " to " + corrected);
    }

    @Test
    void theSquareIsStillASquare() {
        float[] wave = square(110, true);
        double peak = 0;
        for (float sample : wave) {
            peak = Math.max(peak, Math.abs(sample));
        }

        assertEquals(1.0, peak, 0.01, "a square reaches one and no further");
        // a hundred and ten hertz at 48 kHz is 436 frames a cycle: high in the first half, low in the second
        assertTrue(wave[100] > 0.99, "high early in the cycle");
        assertTrue(wave[320] < -0.99, "and low late in it");
    }

    @Test
    void changingTheWaveformBarelyChangesTheLoudness() {
        // not exactly: a square is odd harmonics only, so a closed filter keeps less of it and an
        // open one keeps more. Two and a half decibels either way is the shape being a shape
        for (NovasawSynth patch : new NovasawSynth[] {
                new AcidBassSynth(), new SubBassSynth(), new AnthemLeadSynth(), new TrancePluckSynth()}) {
            double saw = voiceLevel(patch.params().withWaveform(Waveform.SAW));
            double square = 20 * Math.log10(voiceLevel(patch.params().withWaveform(Waveform.SQUARE)) / saw);
            assertTrue(Math.abs(square) < 3.0,
                    patch.getClass().getSimpleName() + " on a square was " + square + " dB off the saw");

            // the sine is deliberately above where measuring puts it: all its energy is in one band
            // where a saw spreads the same across a dozen, and the ear adds loudness across bands,
            // so matching root-mean-square would be matching the wrong thing
            double sine = 20 * Math.log10(voiceLevel(patch.params().withWaveform(Waveform.SINE)) / saw);
            // one sine keeps its fundamental instead of losing it to phase cancellation; a pluck
            // whose filter closes around the fundamental can therefore sit further above a saw
            assertTrue(sine > 0.0 && sine < 7.0,
                    patch.getClass().getSimpleName() + " on a sine should sit a few dB over the saw by "
                            + "measurement so that it matches it by ear, and was " + sine);
        }
    }

    @Test
    void aPatchArrivesOnTheSawItWasWrittenOn() {
        for (NovasawSynth patch : new NovasawSynth[] {new AcidBassSynth(), new AnthemLeadSynth()}) {
            assertEquals(Waveform.SAW, patch.params().waveform());
        }
        assertEquals(1.0f, Waveform.SAW.level(), "and the saw is not scaled at all");
        // the waveform is kept when anything else about the patch changes
        assertEquals(Waveform.SQUARE,
                new AcidBassSynth().params().withWaveform(Waveform.SQUARE).withCutoff(900).waveform());
    }

    @Test
    void sineUsesOneOscillatorSoItsFundamentalCannotCancelInUnison() {
        SynthParams bass = new SubBassSynth().params().withSub(0);
        SynthParams sine = bass.withWaveform(Waveform.SINE);

        assertEquals(1, sine.unisonVoices(), "an 808-style sine needs a stable fundamental");
        assertEquals(1, sine.withVoices(7).unisonVoices(),
                "raising the unison knob must not bring phase cancellation back");
        assertEquals(voiceLevel(sine.withVoices(1)), voiceLevel(sine), 1e-6,
                "the default sine should be as full as its single-oscillator version");
    }

    @Test
    void drivenBassSineKeepsItsFundamentalAndGainsAudibleHarmonics() {
        SynthParams bass = new SubBassSynth().params().withSub(0).withWaveform(Waveform.SINE);
        double hz = 440.0 * Math.pow(2.0, (36 - 69) / 12.0);
        float[] clean = heldVoice(bass.withDrive(0.1f), 36);
        float[] driven = heldVoice(bass.withDrive(2.0f), 36);
        double cleanThird = energyAt(clean, 3 * hz) / energyAt(clean, hz);
        double drivenThird = energyAt(driven, 3 * hz) / energyAt(driven, hz);

        assertTrue(drivenThird > 0.15, "drive should give the sub audible upper harmonics");
        assertTrue(drivenThird > cleanThird * 3,
                "drive should change the tone, not just the measured level");
    }
}
