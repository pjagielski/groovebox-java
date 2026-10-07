package io.groovebox.synth;

import org.junit.jupiter.api.Test;
import io.groovebox.audio.Sample;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sound of the ported patches down to the sample, so that moving the render into
 * {@link NovasawVoice} — or letting a knob feed it — cannot quietly change what a patch sounds like.
 */
class NovasawSynthTest {

    private static final int SAMPLE_RATE = 44_100;

    @Test
    void anthemRendersTheSameSamplesAsItAlwaysHas() {
        Sample rendered = new AnthemLeadSynth().render(64, 11_025, SAMPLE_RATE);

        assertEquals(23_644, rendered.frameCount());
        assertEquals("23644 0.070446 -0.092999 0.000043 80.667221", fingerprint(rendered));
    }

    @Test
    void everyPatchKeepsItsOwnFingerprint() {
        assertEquals("24443 0.106432 0.001758 -0.000034 45.032510",
                fingerprint(new TrancePluckSynth().render(72, 5_512, SAMPLE_RATE)));
        assertEquals("103044 0.133029 -0.030788 -0.000096 211.251538",
                fingerprint(new WidePadSynth().render(48, 22_050, SAMPLE_RATE)));
        assertEquals("62404 0.076450 -0.010338 0.000054 192.694281",
                fingerprint(new ChordsSynth().render(60, 22_050, SAMPLE_RATE)));
    }

    @Test
    void theSubFillsInTheOctaveBelowTheNote() {
        SynthParams dry = new SubBassSynth().params().withSub(0);
        SynthParams withSub = new SubBassSynth().params().withSub(0.8f);
        double noteHz = 440 * Math.pow(2, (40 - 69) / 12.0);

        double without = levelAt(NovasawSynth.render(40, SAMPLE_RATE, SAMPLE_RATE, dry), noteHz / 2);
        double with = levelAt(NovasawSynth.render(40, SAMPLE_RATE, SAMPLE_RATE, withSub), noteHz / 2);

        assertTrue(with > without * 3, "the sub should put real weight an octave down, not a hint");
    }

    @Test
    void theBassPatchesKeepTheirOwnFingerprints() {
        assertEquals("54513 0.107788 -0.048537 0.000018 250.369001",
                fingerprint(new SubBassSynth().render(40, 44_100, SAMPLE_RATE)));
        assertEquals("50367 -0.025291 -0.037999 -0.000379 118.221773",
                fingerprint(new AcidBassSynth().render(45, 44_100, SAMPLE_RATE)));
    }

    @Test
    void theFilterEnvelopeDarkensANoteWhoseLevelHolds() {
        // the level holds at full for the whole second; only the filter's envelope moves
        SynthParams held = SynthParams.of(0.002f, 0.2f, 1.0f, 0.1f, 12, 0, 0, 1, 0,
                300, 0.2f, 8000, 0, 0.001f, 0.15f, 0.0f, 0.1f, 0.5f, 1.0f);
        SynthParams following = held.withFilterEnvelope(0.002f, 0.2f, 1.0f, 0.1f);

        float[] pluck = NovasawSynth.render(57, SAMPLE_RATE, SAMPLE_RATE, held).copyMono();
        float[] open = NovasawSynth.render(57, SAMPLE_RATE, SAMPLE_RATE, following).copyMono();

        assertTrue(brightness(pluck, 22_050) < brightness(pluck, 0) * 0.3,
                "the brightness should be gone half a second in");
        assertTrue(rms(pluck, 22_050) > rms(pluck, 0) * 0.4, "while the note itself still sounds");
        assertTrue(brightness(open, 22_050) > brightness(open, 0) * 0.5,
                "a filter following a held level stays open");
    }

    /** How much of a stretch is edges rather than body: the size of its steps against its size. */
    @Test
    void theDriveKnobAddsGritRatherThanVolume() {
        var patch = new AnthemLeadSynth().params();
        float[] clean = NovasawSynth.render(60, SAMPLE_RATE / 2, SAMPLE_RATE, patch.withDrive(0.1f)).copyMono();
        float[] middling = NovasawSynth.render(60, SAMPLE_RATE / 2, SAMPLE_RATE, patch.withDrive(1.0f)).copyMono();
        float[] hard = NovasawSynth.render(60, SAMPLE_RATE / 2, SAMPLE_RATE, patch.withDrive(2.0f)).copyMono();

        // the shaper on its own is some ten times louder at the top of the knob than at the bottom
        assertEquals(rms(middling, 0), rms(clean, 0), rms(middling, 0) * 0.3, "quiet drive, same level");
        assertEquals(rms(middling, 0), rms(hard, 0), rms(middling, 0) * 0.3, "hard drive, same level");
        // what it does change is the sound: driven hard, the peaks are shaped off and the waveform
        // squares up, so it sits closer to its own average level
        assertTrue(crest(hard) < crest(clean) * 0.9,
                "and grit with it: " + crest(hard) + " against " + crest(clean));
    }

    /** Peak against average: a clean waveform stands well above its own level, a shaped one less. */
    private static double crest(float[] samples) {
        double peak = 0;
        for (float value : samples) {
            peak = Math.max(peak, Math.abs(value));
        }
        return peak / rms(samples, 0);
    }

    private static double brightness(float[] samples, int from) {
        double steps = 0;
        for (int frame = from + 1; frame < from + 2_205; frame++) {
            double step = samples[frame] - samples[frame - 1];
            steps += step * step;
        }
        return Math.sqrt(steps / 2_204) / rms(samples, from);
    }

    private static double rms(float[] samples, int from) {
        double sum = 0;
        for (int frame = from; frame < from + 2_205; frame++) {
            sum += samples[frame] * samples[frame];
        }
        return Math.sqrt(sum / 2_205);
    }

    /** How much of {@code hertz} a sample holds, by correlating it with that frequency. */
    private static double levelAt(Sample sample, double hertz) {
        float[] mono = sample.copyMono();
        double real = 0;
        double imaginary = 0;
        for (int frame = 0; frame < mono.length; frame++) {
            double angle = 2 * Math.PI * hertz * frame / SAMPLE_RATE;
            real += mono[frame] * Math.cos(angle);
            imaginary += mono[frame] * Math.sin(angle);
        }
        return Math.hypot(real, imaginary) / mono.length;
    }

    /** A few frames spread across the sample, plus its energy: enough to catch any change in the DSP. */
    private static String fingerprint(Sample sample) {
        float[] mono = sample.copyMono();
        double energy = 0;
        for (float value : mono) {
            energy += value * value;
        }
        return String.format(Locale.ROOT, "%d %.6f %.6f %.6f %.6f", mono.length,
                mono[mono.length / 8], mono[mono.length / 3], mono[mono.length - 1], energy);
    }
}
