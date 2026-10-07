package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdsrLifecycleTest {
    private static final int RATE = 48_000;

    @Test
    void attackReachesUnityContinuouslyWhenSustainIsUnity() {
        var envelope = new NovasawDsp.Adsr();
        int attackFrames = RATE / 10;
        float beforeLast = 0;
        float last = 0;
        for (int frame = 0; frame < attackFrames; frame++) {
            beforeLast = last;
            last = envelope.next(frame, RATE, 0.1f, 0.2f, 1.0f, 0.1f, RATE);
        }
        assertEquals(1.0f, last, 1e-5f);
        assertTrue(last - beforeLast < 0.001f, "the end of attack must not click");
        for (int frame = attackFrames; frame < RATE / 2; frame++) {
            last = envelope.next(frame, RATE, 0.1f, 0.2f, 1.0f, 0.1f, RATE);
        }
        assertEquals(1.0f, last, 1e-5f);
    }

    @Test
    void releaseRingsBelowAudibilityBeforeVoiceEnds() {
        SynthParams params = new AnthemLeadSynth().params();
        int heldFrames = RATE / 5;
        var voice = new NovasawVoice(57, heldFrames, RATE, () -> params);
        float last = 0;
        int frames = 0;
        while (!voice.finished() && frames < heldFrames + RATE) {
            last = voice.next();
            frames++;
        }
        assertTrue(voice.finished(), "release must have a finite end");
        assertTrue(frames > heldFrames + Math.round(params.releaseSeconds() * RATE),
                "one nominal release time leaves roughly 5.7% of the envelope");
        assertTrue(Math.abs(last) < 0.002f, "the final audio sample should be quiet: " + last);
    }

    @Test
    void renderedSampleAndLiveVoiceEndOnTheSameFrame() {
        SynthParams params = new AnthemLeadSynth().params();
        int heldFrames = RATE / 4;
        var voice = new NovasawVoice(60, heldFrames, RATE, () -> params);
        int frames = 0;
        while (!voice.finished()) {
            voice.next();
            frames++;
            assertTrue(frames < heldFrames + RATE, "voice must finish");
        }
        assertFalse(frames == heldFrames);
        assertEquals(frames, NovasawSynth.render(60, heldFrames, RATE, params).frameCount());
    }
}
