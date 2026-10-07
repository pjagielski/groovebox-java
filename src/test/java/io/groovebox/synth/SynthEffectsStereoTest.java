package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynthEffectsStereoTest {

    private static final int RATE = 44_100;
    private static final int FRAMES = RATE;

    /** Everything on at once, so the fingerprint covers the crusher, both delay lines and the room. */
    private static final EffectParams EVERYTHING = new EffectParams(DelayMode.PING_PONG, 180,
            0.55f, 0.6f, 0.5f, 0.7f, 0.4f, 0.3f, 0.6f, 200, 0.4f);

    @Test
    void aMonoSignalSoundsExactlyAsItDidWhenTheChannelWasMono() {
        assertEquals("-0.125775 -0.015476 -0.096916 -0.029525 1090.326625 721.993567",
                fingerprint(EVERYTHING, SynthEffectsStereoTest::pluck, SynthEffectsStereoTest::pluck));
    }

    @Test
    void theChannelCarriesTwoEarsRatherThanFoldingThemTogether() {
        // a line each, no room around it: what is put on one side should stay there
        EffectParams sided = new EffectParams(DelayMode.MONO, 180, 0.5f, 0.6f, 1.0f,
                0.7f, 0.4f, 0.0f);

        String leftOnly = fingerprint(sided, SynthEffectsStereoTest::pluck, frame -> 0.0f);

        double energyLeft = Double.parseDouble(leftOnly.split(" ")[4]);
        double energyRight = Double.parseDouble(leftOnly.split(" ")[5]);
        assertTrue(energyLeft > 1.0, "the side that was played should be heard: " + energyLeft);
        assertEquals(0.0, energyRight, 1e-9, "the side that was silent should stay silent");
    }

    @Test
    void thePingPongStillCrossesWhenBothEarsFeedIt() {
        // both ears go into the one line it crosses from, so the first repeat is on one side only
        EffectParams pinged = new EffectParams(DelayMode.PING_PONG, 100, 0.6f, 1.0f, 1.0f,
                0.7f, 0.4f, 0.0f);
        SynthEffects effects = new SynthEffects(RATE, () -> pinged);
        float[] stereo = new float[2];
        double firstRepeatLeft = 0;
        double firstRepeatRight = 0;
        for (int frame = 0; frame < RATE / 2; frame++) {
            float impulse = frame == 0 ? 1.0f : 0.0f;
            effects.process(impulse, impulse, stereo);
            // the first repeat lands a delay time in, the second one a delay time after that
            if (frame > RATE / 20 && frame < RATE * 3 / 20) {
                firstRepeatLeft = Math.max(firstRepeatLeft, Math.abs(stereo[0]));
                firstRepeatRight = Math.max(firstRepeatRight, Math.abs(stereo[1]));
            }
        }

        assertTrue(firstRepeatLeft > firstRepeatRight * 4,
                "the first repeat should be on one side: " + firstRepeatLeft + " against " + firstRepeatRight);
    }

    private static String fingerprint(EffectParams params, Signal left, Signal right) {
        SynthEffects effects = new SynthEffects(RATE, () -> params);
        float[] stereo = new float[2];
        double energyLeft = 0;
        double energyRight = 0;
        float[] sampled = new float[4];
        for (int frame = 0; frame < FRAMES; frame++) {
            // a kick every half second, so the ducking's dip and its swell are both in the window
            if (frame % (RATE / 2) == 0) {
                effects.duck();
            }
            effects.process(left.at(frame), right.at(frame), stereo);
            energyLeft += stereo[0] * stereo[0];
            energyRight += stereo[1] * stereo[1];
            switch (frame) {
                case FRAMES / 4 -> {
                    sampled[0] = stereo[0];
                    sampled[1] = stereo[1];
                }
                case FRAMES * 3 / 4 -> {
                    sampled[2] = stereo[0];
                    sampled[3] = stereo[1];
                }
                default -> { }
            }
        }
        return String.format(Locale.ROOT, "%.6f %.6f %.6f %.6f %.6f %.6f",
                sampled[0], sampled[1], sampled[2], sampled[3], energyLeft, energyRight);
    }

    /** A plucked note: a decaying sine with a little of its octave, repeating every quarter second. */
    private static float pluck(int frame) {
        int into = frame % (RATE / 4);
        double decay = Math.exp(-into / (RATE * 0.08));
        double phase = 2 * Math.PI * 110.0 * into / RATE;
        return (float) (decay * (Math.sin(phase) + 0.3 * Math.sin(2 * phase)));
    }

    private interface Signal {
        float at(int frame);
    }
}
