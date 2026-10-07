package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CutoffFloorTest {

    private static final int RATE = 48_000;

    private static double levelDb(SynthParams params, int midiNote) {
        var held = new AtomicReference<>(params);
        int frames = RATE / 2;
        NovasawVoice voice = new NovasawVoice(midiNote, frames, RATE, held::get);
        double sum = 0;
        int counted = 0;
        for (int frame = 0; frame < frames; frame++) {
            float out = voice.next();
            if (frame > RATE / 4) {
                sum += out * out;
                counted++;
            }
        }
        return 20 * Math.log10(Math.max(1e-9, Math.sqrt(sum / counted)));
    }

    /** The acid patch with nothing opening the filter but the knob. */
    private static SynthParams flat() {
        return new AcidBassSynth().params()
                .withFilterEnvelope(0.001f, 0.001f, 0.0f, 0.001f);
    }

    @Test
    void theBottomOfTheCutoffKnobIsNotDead() {
        // it goes to 40 Hz and both filters take 40 Hz; the voice used to clamp what it asked for to
        // 80, so the knob's whole bottom octave did the same thing
        // at middle C, where this patch's key tracking adds nothing; lower down it subtracts
        // hertz faster than the knob's bottom can ask for them - see the other test
        double at80 = levelDb(flat().withCutoff(80), 60);
        double at40 = levelDb(flat().withCutoff(40), 60);

        assertTrue(at40 < at80 - 5, "the bottom octave of the knob should still be taking level off, "
                + "but 40 Hz gave " + at40 + " against 80 Hz's " + at80);
    }

    @Test
    void fourPolesShutABassNoteFurtherThanTwo() {
        // 82 Hz against a 40 Hz cutoff is one octave: twelve decibels for two poles, twice that for four
        double twoPole = levelDb(flat().withCutoff(40), 60);
        double ladder = levelDb(flat().withCutoff(40).withFilter(FilterKind.LADDER).withResonance(0.2f), 60);

        assertTrue(ladder < twoPole - 2, "the ladder should get further down, and gave "
                + ladder + " against " + twoPole);
    }

    @Test
    void thePatchesAreWhereTheyWere() {
        // the floor only ever bit below 80 Hz, which no patch reaches at its own settings; checked
        // through the whole channel before and after the change, and unmoved to a tenth of a decibel
        assertEquals(-29.45, levelDb(new AcidBassSynth().params(), 40), 0.05);
        assertEquals(-22.80, levelDb(new SubBassSynth().params(), 40), 0.05);
    }

    @Test
    void keyTrackingEatsTheBottomOfTheKnobOnALowNote() {
        // acid tracks 22 Hz a semitone, so at MIDI 40 it takes 440 Hz off what the knob asked for.
        // Everything the knob can ask for below that lands on the floor and sounds the same, which
        // is the larger half of why the filter seems to do nothing on a bass note
        double lowest = levelDb(flat().withCutoff(40), 40);
        double middling = levelDb(flat().withCutoff(400), 40);

        assertEquals(lowest, middling, 0.01, "40 Hz and 400 Hz should be indistinguishable down there");
        // and at middle C, where nothing is subtracted, the same two are far apart
        assertTrue(levelDb(flat().withCutoff(400), 60) - levelDb(flat().withCutoff(40), 60) > 15);
    }
}
