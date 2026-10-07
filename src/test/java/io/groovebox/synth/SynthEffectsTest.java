package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynthEffectsTest {

    private static final int SAMPLE_RATE = 1_000;
    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    @Test
    void theDelayBouncesItsRepeatsFromEarToEar() {
        // 100 ms, half of it fed back, tone wide open, nothing but the delay heard
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.5f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 450);

        assertEquals(1.0f, out[LEFT][100], 1e-3f);
        assertEquals(0.0f, out[RIGHT][100], 1e-3f);
        assertEquals(0.0f, out[LEFT][200], 1e-3f);
        assertTrue(out[RIGHT][200] > 0.4f, "the first repeat comes back on the other side");
        assertTrue(out[LEFT][300] > 0.15f && out[LEFT][300] < out[RIGHT][200],
                "and crosses back, quieter each time");
    }

    @Test
    void monoPutsTheSameRepeatInBothEars() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.MONO, 100, 0.5f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 350);

        assertEquals(1.0f, out[LEFT][100], 1e-3f);
        assertEquals(out[LEFT][100], out[RIGHT][100], 1e-6f);
        assertEquals(out[LEFT][200], out[RIGHT][200], 1e-6f);
    }

    @Test
    void stereoGivesTheRightEarTheShorterTime() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.STEREO, 150, 0.4f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 400);

        // the left repeat lands at its own time, the right at two thirds of it
        assertEquals(1.0f, out[LEFT][150], 1e-3f);
        assertEquals(1.0f, out[RIGHT][100], 1e-3f);
        assertEquals(0.0f, out[RIGHT][150], 1e-3f);
    }

    @Test
    void tapeDriftsAwayFromTheTimeItWasGiven() {
        var steady = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.MONO, 200, 0.6f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));
        var tape = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.TAPE, 200, 0.6f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] straight = impulse(steady, 3_000);
        float[][] warbled = impulse(tape, 3_000);

        assertTrue(difference(straight[LEFT], warbled[LEFT]) > 0.01,
                "the drifting read head should not land where a steady one does");
        for (float value : warbled[LEFT]) {
            assertTrue(Math.abs(value) <= 1.05f, "warble is no excuse for getting louder");
        }
    }

    @Test
    void theToneKnobDarkensEachRepeat() {
        float bright = repeatLevel(1.0f);
        float dark = repeatLevel(0.2f);

        assertTrue(dark < bright * 0.9f, "a closed tone should take the edge off the repeats");
    }

    @Test
    void feedbackNearTheTopStaysBounded() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 50, 0.95f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 20_000);

        for (int channel = 0; channel < 2; channel++) {
            for (float value : out[channel]) {
                assertTrue(Math.abs(value) <= 1.05f, "a runaway delay would blow past its input");
            }
        }
    }

    @Test
    void theReverbRingsOnInStereoAndDiesAway() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.0f, 1.0f, 0.0f, 0.7f, 0.4f, 1.0f));

        float[][] out = impulse(effects, 8_000);

        assertTrue(energy(out[LEFT], 0, 2_000) > 0.001, "the room should answer the impulse");
        assertTrue(energy(out[LEFT], 6_000, 8_000) < energy(out[LEFT], 0, 2_000), "and then die away");
        // the right channel's twelve delay lines are longer, so the two ears never hear the same room
        assertTrue(difference(out[LEFT], out[RIGHT]) > 0.0001, "the two channels should differ");
        for (int channel = 0; channel < 2; channel++) {
            for (float value : out[channel]) {
                assertTrue(Math.abs(value) <= 1.0f, "a reverb louder than its input is feeding back");
            }
        }
    }

    @Test
    void aBiggerRoomRingsForLonger() {
        float small = tailEnergy(0.1f);
        float large = tailEnergy(0.95f);

        assertTrue(large > small * 2, "size should stretch the tail, not just colour it");
    }

    /** The level of the first repeat, which is what the tone knob is expected to change. */
    private static float repeatLevel(float tone) {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.5f, tone, 1.0f, 0.0f, 0.0f, 0.0f));
        float[][] out = impulse(effects, 260);
        float peak = 0;
        for (int frame = 180; frame < 260; frame++) {
            peak = Math.max(peak, Math.abs(out[RIGHT][frame]));
        }
        return peak;
    }

    private static float tailEnergy(float size) {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.0f, 1.0f, 0.0f, size, 0.2f, 1.0f));
        float[][] out = impulse(effects, 8_000);
        return (float) energy(out[LEFT], 5_000, 8_000);
    }

    /** One frame in at full level, then silence: what comes out is the effect's own tail. */
    private static float[][] impulse(SynthEffects effects, int frames) {
        float[][] out = new float[2][frames];
        float[] stereo = new float[2];
        for (int frame = 0; frame < frames; frame++) {
            float impulse = frame == 0 ? 1.0f : 0.0f;
            effects.process(impulse, impulse, stereo);
            out[LEFT][frame] = stereo[LEFT];
            out[RIGHT][frame] = stereo[RIGHT];
        }
        return out;
    }

    private static double energy(float[] values, int from, int to) {
        double energy = 0;
        for (int index = from; index < to; index++) {
            energy += values[index] * values[index];
        }
        return energy;
    }

    private static double difference(float[] left, float[] right) {
        double difference = 0;
        for (int index = 0; index < left.length; index++) {
            difference += Math.abs(left[index] - right[index]);
        }
        return difference;
    }

    @Test
    void aKickDucksTheChannelAndItSwellsBackOverTheRelease() {
        // dry only, so what comes out is the input times the sidechain's gain
        var effects = new SynthEffects(44_100, () -> new EffectParams(
                DelayMode.MONO, 100, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.8f, 200));
        float[] out = new float[2];
        for (int frame = 0; frame < 4_410; frame++) {
            effects.process(1.0f, 1.0f, out);
        }
        assertEquals(1.0f, out[LEFT], 1e-4f);

        effects.duck();
        float[] heard = new float[44_100 / 2];
        for (int frame = 0; frame < heard.length; frame++) {
            effects.process(1.0f, 1.0f, out);
            heard[frame] = out[LEFT];
            assertEquals(out[LEFT], out[RIGHT], 1e-6f);
        }

        assertTrue(heard[0] > 0.9f, "no click: the drop takes a moment");
        assertEquals(0.2f, heard[441], 0.02f, "10 ms in, down by the depth");
        assertEquals(0.6f, heard[4_410], 0.03f, "half way through the release, half way back");
        assertEquals(1.0f, heard[9_261], 1e-3f, "and whole again once the release is over");
    }

    @Test
    void theCrusherHoldsAndRoundsTheChannelBeforeTheDelayAndReverb() {
        // 44.1 kHz, so the crusher's own rate is what holds the samples, not the channel's
        var crushed = new SynthEffects(44_100,
                () -> new EffectParams(DelayMode.MONO, 100, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f)
                        .withCrush(1.0f));
        float[] out = new float[2];
        int changes = 0;
        float before = 0;

        for (int frame = 0; frame < 4_410; frame++) {
            float tone = (float) (0.5 * Math.sin(2 * Math.PI * 220 * frame / 44_100));
            crushed.process(tone, tone, out);
            if (frame > 0 && out[LEFT] != before) {
                changes++;
            }
            before = out[LEFT];
            // wide open, the crusher rounds to sixteen levels a side: nothing lands between them
            assertEquals(Math.round(out[LEFT] * 8) / 8.0f, out[LEFT], 1e-6f);
        }

        // about 700 samples a second: some 70 of them in a tenth of a second, not 4410
        assertEquals(70, changes, 12);
    }

    @Test
    void withoutDriveTheAmplifierPassesAsItIs() {
        // where a synth leaves it: its voices are driven already, and driving them again is twice
        var effects = new SynthEffects(44_100, () -> dry());
        float[] out = new float[2];

        for (int frame = 0; frame < 500; frame++) {
            float value = (float) (0.3 * Math.sin(frame * 0.1));
            effects.process(value, value, out);
            assertEquals(value, out[LEFT], 1e-6f, "at drive 0 the shaper is not called at all");
        }
    }

    @Test
    void driveAddsHarmonicsWithoutAddingLevel() {
        float[] clean = amplified(dry(), 0.35f);
        float[] driven = amplified(dry().driven(Saturation.DIODE, 1.2f, 1.0f), 0.35f);

        // a 220 Hz sine has no third harmonic at all until something bends it: measured, it goes
        // from 2.7e-10 of the signal to 0.031 of it, while the level moves by 1.3 dB. Levelled, so
        // what the drive changes is the tone and not the loudness
        double decibels = 20 * Math.log10(rootMeanSquare(driven) / rootMeanSquare(clean));
        assertEquals(0.0, decibels, 3.0, "a levelled shape changes the sound, not how loud it is");
        assertTrue(thirdHarmonic(clean) < 1e-6, "a sine through a channel at rest: " + thirdHarmonic(clean));
        assertTrue(thirdHarmonic(driven) > 0.02, "and driven: " + thirdHarmonic(driven));
    }

    @Test
    void everyShapeIsADifferentSoundAndTheTrimSetsTheLevelAfterThem() {
        float[] diode = amplified(dry().driven(Saturation.DIODE, 1.2f, 1.0f), 0.35f);
        float[] fold = amplified(dry().driven(Saturation.FOLD, 1.2f, 1.0f), 0.35f);
        float[] halved = amplified(dry().driven(Saturation.DIODE, 1.2f, 0.5f), 0.35f);

        // a fold and a diode are different species rather than two settings of one: measured, they
        // part company by 0.22 on a signal whose own peak is 0.35
        assertTrue(distance(diode, fold) > 0.1,
                "a fold and a diode should not be near each other: " + distance(diode, fold));
        for (int frame = 0; frame < diode.length; frame++) {
            assertEquals(diode[frame] * 0.5f, halved[frame], 1e-6f, "the trim comes after the shape");
        }
    }

    @Test
    void withTheFilterOffTheChannelPassesAsItIs() {
        var effects = new SynthEffects(44_100, () -> dry().filtered(ChannelFilter.OFF, 200, 0.5f));
        float[] out = new float[2];

        for (int frame = 0; frame < 2_000; frame++) {
            float value = (float) (0.3 * Math.sin(frame * 0.1));
            effects.process(value, value, out);
            assertEquals(value, out[LEFT], 1e-6f, "off is a bypass, not a filter wide open");
        }
    }

    @Test
    void theLadderCutsTwiceAsSteeplyAsTheTwoPole() {
        // a 4 kHz tone with the cutoff at 400: three and a third octaves above it
        double open = level(amplified(dry().filtered(ChannelFilter.OFF, 400, 0.0f), 0.4f, 4_000));
        double flat = level(amplified(dry().filtered(ChannelFilter.TWO_POLE, 400, 0.0f), 0.4f, 4_000));
        double steep = level(amplified(dry().filtered(ChannelFilter.LADDER, 400, 0.0f), 0.4f, 4_000));

        double twoPoleDown = 20 * Math.log10(flat / open);
        double ladderDown = 20 * Math.log10(steep / open);
        // measured: -40.5 dB and -81.1 dB, which over 3.32 octaves is 12.2 and 24.4 dB an octave -
        // the textbook figures for two poles and four, and the ladder exactly twice the other
        assertEquals(-40.5, twoPoleDown, 2.0, "twelve decibels an octave");
        assertEquals(2.0, ladderDown / twoPoleDown, 0.15, "and the ladder twice as steep");
    }

    @Test
    void turningTheFilterOnUnderAToneDoesNotStep() {
        var changing = new java.util.concurrent.atomic.AtomicReference<>(
                dry().filtered(ChannelFilter.OFF, 300, 0.4f));
        var effects = new SynthEffects(44_100, changing::get);
        float[] out = new float[2];
        for (int frame = 0; frame < 4_410; frame++) {
            float tone = tone(frame, 0.4f, 2_000);
            effects.process(tone, tone, out);
        }

        changing.set(dry().filtered(ChannelFilter.LADDER, 300, 0.4f));
        float worst = 0;
        float before = out[LEFT];
        for (int frame = 4_410; frame < 8_820; frame++) {
            float tone = tone(frame, 0.4f, 2_000);
            effects.process(tone, tone, out);
            worst = Math.max(worst, Math.abs(out[LEFT] - before));
            before = out[LEFT];
        }

        // a 2 kHz tone at 0.4 climbs by at most 0.4*2*pi*2000/44100 = 0.1140 a sample on its own.
        // Measured through the switch: 0.1124 - the crossfade adds nothing to what the tone
        // was already doing, which is what makes reaching for the bypass mid-take silent
        assertTrue(worst <= 0.1140f, "the switch should not step further than the tone: " + worst);
    }

    @Test
    void theFilterEnvelopeOpensOnAStrikeAndCloses() {
        EffectParams sweeping = dry()
                .filtered(ChannelFilter.LADDER, 200, 0.2f)
                .sweeping(6_000, 0.001f, 0.08f, 0.0f, 0.05f);
        var effects = new SynthEffects(44_100, () -> sweeping);
        float[] out = new float[2];
        // settled with the filter shut, so what is measured is the envelope and not the glide
        for (int frame = 0; frame < 44_100; frame++) {
            float value = tone(frame, 0.4f, 3_000);
            effects.process(value, value, out);
        }
        double shut = peakOver(effects, 2_000, 44_100);

        effects.strike();
        double justStruck = peakOver(effects, 800, 46_100);
        // a decay of 80 ms is all but over after four of them; measured only past that, or what is
        // measured is the middle of the sweep rather than the end of it
        peakOver(effects, 13_230, 47_000);
        double wellAfter = peakOver(effects, 2_000, 60_230);

        assertTrue(justStruck > shut * 8,
                "the strike should open it: " + justStruck + " against " + shut);
        assertTrue(wellAfter < justStruck / 4,
                "and it should close again: " + wellAfter + " against " + justStruck);
    }

    @Test
    void withNoEnvelopeAmountAStrikeChangesNothing() {
        EffectParams still = dry().filtered(ChannelFilter.LADDER, 600, 0.2f);
        var effects = new SynthEffects(44_100, () -> still);
        float[] out = new float[2];
        for (int frame = 0; frame < 44_100; frame++) {
            float value = tone(frame, 0.4f, 3_000);
            effects.process(value, value, out);
        }
        double before = peakOver(effects, 2_000, 44_100);

        effects.strike();
        double after = peakOver(effects, 2_000, 46_100);

        // where a synth leaves it: its own voices have their own envelopes and this one is not theirs
        assertEquals(before, after, before * 0.02, "no amount, no sweep");
    }

    /** The loudest the channel gets over {@code frames}, carrying on from {@code from}. */
    private static double peakOver(SynthEffects effects, int frames, int from) {
        float[] out = new float[2];
        double peak = 0;
        for (int frame = 0; frame < frames; frame++) {
            float value = tone(from + frame, 0.4f, 3_000);
            effects.process(value, value, out);
            peak = Math.max(peak, Math.abs(out[LEFT]));
        }
        return peak;
    }

    private static float tone(int frame, float level, double hz) {
        return (float) (level * Math.sin(2 * Math.PI * hz * frame / 44_100));
    }

    /** A second of a tone through {@code params}, left channel, past the glides. */
    private static float[] amplified(EffectParams params, float level, double hz) {
        var effects = new SynthEffects(44_100, () -> params);
        float[] out = new float[2];
        float[] heard = new float[4_410];
        // a tenth of a second first, so the filter is where the knobs say before anything is measured
        for (int frame = 0; frame < 4_410; frame++) {
            float value = tone(frame, level, hz);
            effects.process(value, value, out);
        }
        for (int frame = 0; frame < heard.length; frame++) {
            float value = tone(frame + 4_410, level, hz);
            effects.process(value, value, out);
            heard[frame] = out[LEFT];
        }
        return heard;
    }

    private static double level(float[] heard) {
        return rootMeanSquare(heard);
    }

    /** Effects with nothing on but the amplifier, so what comes out is the amplifier alone. */
    private static EffectParams dry() {
        return new EffectParams(DelayMode.MONO, 100, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    /** A second of a 220 Hz sine at {@code level} through {@code params}, left channel. */
    private static float[] amplified(EffectParams params, float level) {
        var effects = new SynthEffects(44_100, () -> params);
        float[] out = new float[2];
        float[] heard = new float[4_410];
        for (int frame = 0; frame < heard.length; frame++) {
            float tone = (float) (level * Math.sin(2 * Math.PI * 220 * frame / 44_100));
            effects.process(tone, tone, out);
            heard[frame] = out[LEFT];
        }
        return heard;
    }

    private static double rootMeanSquare(float[] heard) {
        double sum = 0;
        for (float value : heard) {
            sum += value * value;
        }
        return Math.sqrt(sum / heard.length);
    }

    /** How much of {@code heard} sits at three times 220 Hz, relative to the whole. */
    private static double thirdHarmonic(float[] heard) {
        double real = 0;
        double imaginary = 0;
        for (int frame = 0; frame < heard.length; frame++) {
            double angle = 2 * Math.PI * 660 * frame / 44_100;
            real += heard[frame] * Math.cos(angle);
            imaginary += heard[frame] * Math.sin(angle);
        }
        return Math.hypot(real, imaginary) / heard.length;
    }

    /** The largest gap between two channels of the same signal shaped two ways. */
    private static double distance(float[] one, float[] other) {
        double worst = 0;
        for (int frame = 0; frame < one.length; frame++) {
            worst = Math.max(worst, Math.abs(one[frame] - other[frame]));
        }
        return worst;
    }

    @Test
    void withoutCrushTheChannelPassesAsItIs() {
        var effects = new SynthEffects(44_100,
                () -> new EffectParams(DelayMode.MONO, 100, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        float[] out = new float[2];

        for (int frame = 0; frame < 500; frame++) {
            float value = (float) (0.3 * Math.sin(frame * 0.1));
            effects.process(value, value, out);
            assertEquals(value, out[LEFT], 1e-6f);
        }
    }

    @Test
    void withoutDepthAKickChangesNothing() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.MONO, 100, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        float[] out = new float[2];

        effects.duck();
        for (int frame = 0; frame < 50; frame++) {
            effects.process(0.5f, 0.5f, out);
            assertEquals(0.5f, out[LEFT], 1e-6f);
        }
    }
}
