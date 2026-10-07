package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveParamSmoothingTest {
    private static final int RATE = 44_100;

    @Test
    void outputTrimRampsDownWithoutAnImmediateStep() {
        SynthParams patch = patch();
        AtomicReference<SynthParams> changed = new AtomicReference<>(patch);
        NovasawVoice live = new NovasawVoice(60, RATE, RATE, changed::get);
        NovasawVoice reference = new NovasawVoice(60, RATE, RATE, () -> patch);
        for (int i = 0; i < RATE / 10; i++) {
            live.next();
            reference.next();
        }
        changed.set(withTrim(patch, 0));
        float firstLive = live.next();
        float firstReference = reference.next();
        assertTrue(Math.abs(firstLive - firstReference) < 0.01f,
                "one parameter update must not mute a running note in one sample");
        for (int i = 0; i < RATE / 100; i++) live.next();
        assertTrue(Math.abs(live.next()) < 0.001f, "trim must reach zero after the short ramp");
    }

    @Test
    void filterSwitchStartsFromTheOldOutputAndCompletes() {
        SynthParams patch = patch();
        AtomicReference<SynthParams> changed = new AtomicReference<>(patch);
        NovasawVoice live = new NovasawVoice(60, RATE, RATE, changed::get);
        NovasawVoice reference = new NovasawVoice(60, RATE, RATE, () -> patch);
        for (int i = 0; i < RATE / 10; i++) {
            live.next();
            reference.next();
        }
        changed.set(patch.withFilter(FilterKind.LADDER));
        assertTrue(Math.abs(live.next() - reference.next()) < 0.01f,
                "switching filter must preserve continuity at the first frame");
        double difference = 0;
        for (int i = 0; i < RATE / 50; i++) {
            difference += Math.abs(live.next() - reference.next());
        }
        assertTrue(difference > 0.1, "the switch must actually reach the second filter");
    }

    private static SynthParams patch() {
        return SynthParams.of(0.001f, 0.01f, 1, 0.1f, 0, 0, 0, 1, 0,
                4000, 0, 0, 0, 0.1f, 1).withVoices(1);
    }

    private static SynthParams withTrim(SynthParams p, float trim) {
        return new SynthParams(p.attackSeconds(), p.decaySeconds(), p.sustainLevel(), p.releaseSeconds(),
                p.detuneCents(), p.subLevel(), p.vibratoCents(), p.motionRateHz(), p.motion(),
                p.cutoffHz(), p.resonance(), p.filterEnvAmountHz(), p.keyTrackHzPerSemitone(),
                p.filterAttackSeconds(), p.filterDecaySeconds(), p.filterSustainLevel(), p.filterReleaseSeconds(),
                p.drive(), trim, p.unisonGain(), p.resonanceCompensation(), p.shape(), p.filter(),
                p.unisonVoices(), p.waveform());
    }
}
