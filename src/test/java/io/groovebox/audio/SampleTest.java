package io.groovebox.audio;

import io.groovebox.model.Envelope;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SampleTest {

    @Test
    void decayReachesAThousandthAtTheDecayTimeAndStopsThere() {
        Sample shaped = ones(100).shaped(new Envelope(0.01, 0.0), -1, 1_000);

        assertEquals(10, shaped.frameCount());
        assertEquals(1.0f, shaped.valueAt(0));
        assertEquals((float) Math.pow(1000, -0.5), shaped.valueAt(5), 1e-6f);
    }

    @Test
    void releaseFadesOutOnceTheNoteHasEnded() {
        Sample shaped = ones(100).shaped(new Envelope(0.0, 0.004), 5, 1_000);

        assertEquals(9, shaped.frameCount());
        assertEquals(1.0f, shaped.valueAt(5));
        assertEquals(0.5f, shaped.valueAt(7));
    }

    @Test
    void noEnvelopeLeavesTheSampleAlone() {
        Sample sample = ones(3);

        assertSame(sample, sample.shaped(Envelope.NONE, 1, 1_000));
    }

    @Test
    void tunedAnOctaveUpItPlaysTwiceAsFastAndLastsHalfAsLong() {
        Sample tuned = ramp(8).shaped(new Envelope(0.0, 0.0, 12), -1, 1_000);

        assertEquals(4, tuned.frameCount());
        assertEquals(0.0f, tuned.valueAt(0));
        assertEquals(2.0f, tuned.valueAt(1), 1e-6f);
        assertEquals(6.0f, tuned.valueAt(3), 1e-6f);
    }

    @Test
    void tunedAnOctaveDownItFillsTheFramesBetweenWithTheLineThroughThem() {
        Sample tuned = ramp(4).shaped(new Envelope(0.0, 0.0, -12), -1, 1_000);

        assertEquals(8, tuned.frameCount());
        assertEquals(0.5f, tuned.valueAt(1), 1e-6f);
        assertEquals(3.0f, tuned.valueAt(6), 1e-6f);
    }

    @Test
    void aDecayIsTimedOnTheTunedSampleAsItIsHeard() {
        // up an octave the sample lasts 50 frames; the decay cuts it at 10 of those, not of the original
        Sample tuned = ones(100).shaped(new Envelope(0.01, 0.0, 12), -1, 1_000);

        assertEquals(10, tuned.frameCount());
        assertEquals((float) Math.pow(1000, -0.5), tuned.valueAt(5), 1e-6f);
    }

    private static Sample ramp(int frames) {
        float[] data = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            data[frame] = frame;
        }
        return Sample.mono(data);
    }

    private static Sample ones(int frames) {
        float[] data = new float[frames];
        Arrays.fill(data, 1.0f);
        return Sample.mono(data);
    }
}
