package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnisonVoicesTest {

    private static final int RATE = 48_000;

    private static double levelDb(SynthParams params) {
        var held = new AtomicReference<>(params);
        int frames = RATE / 2;
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
        return 20 * Math.log10(Math.max(1e-9, Math.sqrt(sum / counted)));
    }

    @Test
    void sevenSawsAreExactlyWhatTheyWere() {
        // every patch was written and levelled on seven; the count must cost nothing at seven
        for (NovasawSynth patch : new NovasawSynth[] {
                new AnthemLeadSynth(), new SubBassSynth(), new AcidBassSynth(), new TrancePluckSynth()}) {
            assertEquals(patch.params().unisonGain(), patch.params().withVoices(7).unisonGain(), 1e-7f,
                    patch.getClass().getSimpleName());
            assertEquals(7, patch.params().unisonVoices(), "and seven is what a patch arrives with");
        }
    }

    @Test
    void turningTheSawsDownBarelyChangesTheLoudness() {
        // the saws cancel as much as they add, by an amount that is not smooth in their number, so
        // this is held to a few decibels rather than to nothing - measured, not hoped for
        for (NovasawSynth patch : new NovasawSynth[] {
                new AnthemLeadSynth(), new SubBassSynth(), new AcidBassSynth()}) {
            double seven = levelDb(patch.params().withVoices(7));
            for (int count = 1; count <= 7; count++) {
                double off = levelDb(patch.params().withVoices(count)) - seven;
                assertTrue(Math.abs(off) < 3.0,
                        patch.getClass().getSimpleName() + " at " + count + " voices was " + off + " dB off");
            }
        }
    }

    @Test
    void oneSawIsNotDetunedAtAll() {
        // what a 303 has, and what seven saws at any detune cannot be
        SynthParams one = new AcidBassSynth().params().withVoices(1);
        assertEquals(1, one.unisonVoices());
        // the count is kept when anything else about the patch is changed
        assertEquals(1, one.withCutoff(900).unisonVoices(), "a cutoff change must not put the saws back");
        assertEquals(1, one.withDrive(0.5f).unisonVoices());
    }

    @Test
    void theCountStaysWithinWhatThereArePhasesFor() {
        SynthParams params = new SubBassSynth().params();
        assertEquals(1, params.withVoices(0).unisonVoices());
        assertEquals(7, params.withVoices(99).unisonVoices());
    }
}
