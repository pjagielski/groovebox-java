package io.groovebox.synth;

import io.groovebox.audio.VoiceSource;

import java.util.function.Supplier;

import static io.groovebox.synth.NovasawDsp.Adsr;
import static io.groovebox.synth.NovasawDsp.LowpassFilter;
import static io.groovebox.synth.NovasawDsp.clamp;
import static io.groovebox.synth.NovasawDsp.deterministicPhaseJitter;
import static io.groovebox.synth.NovasawDsp.polyBlepSaw;
import static io.groovebox.synth.NovasawDsp.polyBlepSquare;
import static io.groovebox.synth.NovasawDsp.wrapTwoPi;
import static io.groovebox.synth.NovasawDsp.wrapUnitPhase;

/**
 * One sounding note of the novasaw engine: 7 unison sawtooths, a diode waveshaper and a resonant
 * lowpass, advanced one frame at a time. Two envelopes run side by side: one sets the level, the
 * other opens the filter by {@code filterEnvAmountHz}, so a note can keep sounding after its
 * brightness has gone, which is what makes a pluck rather than just a short note. All the state the old {@code render} loop kept in local
 * variables lives here instead, so the note can be played out over many blocks.
 *
 * <p>The parameters are read from the supplier on every frame rather than captured once. Hand it a
 * fixed value and the note sounds exactly as a rendered sample would; hand it a patch whose
 * parameters a panel is moving, and the filter opens under the finger while the note is held.
 */
public final class NovasawVoice implements VoiceSource {

    static final int UNISON_VOICES = 7;

    /**
     * Where each saw sits in the detune, from one edge to the other, for every count of them. Seven
     * is the shape the patches were written on and is left exactly as it was; the smaller counts are
     * drawn from the same shape and kept symmetric, so turning the voices down narrows the chorus
     * rather than leaning it to one side. One saw sits in the middle and is not detuned at all,
     * which is what a bass wants and what a 303 has.
     */
    private static final double[][] UNISON_OFFSETS = {
            {},
            {0.0},
            {-1.0, 1.0},
            {-1.0, 0.0, 1.0},
            {-1.0, -0.23, 0.23, 1.0},
            {-1.0, -0.58, 0.0, 0.58, 1.0},
            {-1.0, -0.58, -0.23, 0.23, 0.58, 1.0},
            {-1.0, -0.58, -0.23, 0.0, 0.23, 0.58, 1.0},
    };
    // the shaper used to carry the drive's own gain, some five times over at the drive a patch
    // sits at; now that it is levelled, that gain lives here instead
    private static final float MAX_OUTPUT_GAIN = 1.046f;
    /** Loud enough that a sub at 1.0 stands up to the seven saws without swamping them. */
    private static final float SUB_GAIN = 2.2f;
    /** The lowest the filter is asked for, matching the knob and what the filters themselves take. */
    static final float CUTOFF_FLOOR = 40.0f;
    private static final double CENTS_TO_LOG_RATIO = Math.log(2.0) / 1200.0;

    private final Supplier<SynthParams> params;
    private final int midiNote;
    private final int heldFrames;
    private final int sampleRate;
    private final double frequency;
    private final double[] phase = new double[UNISON_VOICES];
    private final double[] driftPhase = new double[UNISON_VOICES];
    private final LowpassFilter filter = new LowpassFilter();
    private final DiodeLadder ladder = new DiodeLadder();
    private final Adsr amplitude = new Adsr();
    private final Adsr filterEnvelope = new Adsr();
    private final float deClickStep;
    private final Ramp detune;
    private final Ramp sub;
    private final Ramp resonance;
    private final Ramp drive;
    private final Ramp outputTrim;
    private final Ramp unisonGain;
    private final LogRamp cutoff;
    private final double[] detuneRatios = new double[UNISON_VOICES];
    private float lastDetune = Float.NaN;
    private int lastVoices;
    private float filterMix;
    private final float filterMixStep;
    private FilterKind targetFilter;

    private double motionPhase;
    private double subPhase;
    private float deClick;
    private int frame;

    public NovasawVoice(int midiNote, int heldFrames, int sampleRate, Supplier<SynthParams> params) {
        this.params = params;
        this.midiNote = midiNote;
        this.heldFrames = heldFrames;
        this.sampleRate = sampleRate;
        this.frequency = 440.0 * Math.pow(2.0, (midiNote - 69) / 12.0);
        this.deClickStep = (float) (1.0 / (0.006 * sampleRate));
        SynthParams initial = params.get();
        int shortRamp = Math.max(1, Math.round(sampleRate * 0.005f));
        int longRamp = Math.max(1, Math.round(sampleRate * 0.010f));
        detune = new Ramp(initial.detuneCents(), longRamp);
        sub = new Ramp(initial.subLevel(), shortRamp);
        resonance = new Ramp(initial.resonance(), longRamp);
        drive = new Ramp(initial.drive(), shortRamp);
        outputTrim = new Ramp(initial.outputTrim(), shortRamp);
        unisonGain = new Ramp(initial.unisonGain(), longRamp);
        cutoff = new LogRamp(initial.cutoffHz(), longRamp);
        targetFilter = initial.filter();
        filterMix = targetFilter == FilterKind.LADDER ? 1.0f : 0.0f;
        filterMixStep = 1.0f / Math.max(1, Math.round(sampleRate * 0.008f));
        // spread across the cycle by however many are going to be used, not by however many there
        // could be: at three saws, dividing by seven left all three inside the first third of it,
        // so their steps nearly coincided and what should be a chorus came out as one hard edge
        int spread = Math.max(1, Math.min(UNISON_VOICES, params.get().unisonVoices()));
        for (int unison = 0; unison < UNISON_VOICES; unison++) {
            double evenPhase = (double) (unison % spread) / spread;
            phase[unison] = wrapUnitPhase(evenPhase + deterministicPhaseJitter(midiNote, unison, heldFrames) * 0.19);
            driftPhase[unison] = deterministicPhaseJitter(midiNote + 17, unison, heldFrames) * 2.0 * Math.PI;
        }
        this.motionPhase = deterministicPhaseJitter(midiNote, UNISON_VOICES, heldFrames) * 2.0 * Math.PI;
    }

    @Override
    public float next() {
        SynthParams current = params.get();
        float detuneCents = detune.next(current.detuneCents());
        float subLevel = sub.next(current.subLevel());
        float resonanceValue = resonance.next(current.resonance());
        float driveValue = drive.next(current.drive());
        float trimValue = outputTrim.next(current.outputTrim());
        float gainValue = unisonGain.next(current.unisonGain());
        float cutoffValue = cutoff.next(current.cutoffHz());
        if (Float.compare(lastDetune, detuneCents) != 0 || lastVoices != current.unisonVoices()) {
            lastDetune = detuneCents;
            lastVoices = current.unisonVoices();
            double[] offsets = UNISON_OFFSETS[current.unisonVoices()];
            for (int i = 0; i < offsets.length; i++) {
                detuneRatios[i] = Math.pow(2.0, offsets[i] * detuneCents / 1200.0);
            }
        }
        float envelope = amplitude.next(frame, heldFrames, current.attackSeconds(), current.decaySeconds(),
                current.sustainLevel(), current.releaseSeconds(), sampleRate);
        float filterLevel = filterEnvelope.next(frame, heldFrames, current.filterAttackSeconds(),
                current.filterDecaySeconds(), current.filterSustainLevel(), current.filterReleaseSeconds(),
                sampleRate);
        deClick = Math.min(1.0f, deClick + deClickStep);

        double vibrato = current.vibratoCents() == 0.0f ? 0.0
                : Math.sin(motionPhase) * current.vibratoCents();
        double vibratoRatio = vibrato == 0.0 ? 1.0 : Math.exp(vibrato * CENTS_TO_LOG_RATIO);
        motionPhase = wrapTwoPi(motionPhase + 2.0 * Math.PI * current.motionRateHz() / sampleRate);

        float mono = 0.0f;
        int voices = current.unisonVoices();
        for (int unison = 0; unison < voices; unison++) {
            double driftCents = current.motion() == 0.0f ? 0.0
                    : Math.sin(driftPhase[unison]) * current.motion() * 0.55;
            double driftRatio = driftRatio(driftCents);
            float phaseIncrement = (float) (frequency * detuneRatios[unison] * vibratoRatio * driftRatio / sampleRate);
            mono += switch (current.waveform()) {
                case SAW -> polyBlepSaw((float) phase[unison], phaseIncrement);
                case SQUARE -> polyBlepSquare((float) phase[unison], phaseIncrement);
                case SINE -> (float) Math.sin(2.0 * Math.PI * phase[unison]);
            };

            phase[unison] += phaseIncrement;
            if (phase[unison] >= 1.0) {
                phase[unison] -= Math.floor(phase[unison]);
            }
            driftPhase[unison] =
                    wrapTwoPi(driftPhase[unison] + 2.0 * Math.PI * (0.035 + 0.011 * unison) / sampleRate);
        }

        if (subLevel > 0) {
            // a sine an octave down, under the saws and through the same filter and drive: what
            // gives a bass line its fundamental, since detuned saws alone come out thin
            mono += (float) Math.sin(subPhase) * subLevel * SUB_GAIN;
        }
        subPhase = wrapTwoPi(subPhase + Math.PI * frequency / sampleRate);

        float voiceSample = mono * current.waveform().level() * envelope * deClick;
        float shaped = current.shape().levelled(voiceSample * gainValue * 0.55f, driveValue)
                * MAX_OUTPUT_GAIN * trimValue;
        float dynamicCutoff = clamp(
                cutoffValue + filterLevel * current.filterEnvAmountHz()
                        + (midiNote - 60) * current.keyTrackHzPerSemitone(),
                // forty, which is where the knob's bottom is and where both filters will go. It
                // used to stop at eighty, so the knob's whole bottom octave did the same thing and
                // a bass note could never be shut out - eighty hertz is a bass note.
                CUTOFF_FLOOR, 18000.0f);
        frame++;
        if (current.filter() != targetFilter) {
            targetFilter = current.filter();
            if (filterMix == 0.0f && targetFilter == FilterKind.LADDER) {
                ladder.reset();
            } else if (filterMix == 1.0f && targetFilter == FilterKind.TWO_POLE) {
                filter.reset();
            }
        }
        filterMix = clamp(filterMix + (targetFilter == FilterKind.LADDER ? filterMixStep : -filterMixStep), 0, 1);
        float filtered;
        if (filterMix == 0.0f) {
            filtered = filter.process(shaped, dynamicCutoff, resonanceValue, sampleRate);
        } else if (filterMix == 1.0f) {
            filtered = ladder.process(shaped, dynamicCutoff, resonanceValue, sampleRate);
        } else {
            float twoPole = filter.process(shaped, dynamicCutoff, resonanceValue, sampleRate);
            float fourPole = ladder.process(shaped, dynamicCutoff, resonanceValue, sampleRate);
            filtered = twoPole + (fourPole - twoPole) * filterMix;
        }
        return filtered / (1.0f + resonanceValue * 0.38f);
    }

    @Override
    public boolean finished() {
        return frame > heldFrames && (amplitude.finished()
                || frame - heldFrames >= releaseFrames(params.get(), sampleRate));
    }

    /** A safety limit for the release tail; ordinary voices end at the envelope's silence threshold. */
    static int releaseFrames(SynthParams params, int sampleRate) {
        return Math.max(1, (int) Math.ceil(Math.max(0.001f, params.releaseSeconds()) * sampleRate * 4));
    }

    static double driftRatio(double cents) {
        double x = cents * CENTS_TO_LOG_RATIO;
        return 1.0 + x + 0.5 * x * x;
    }

    private static final class Ramp {
        private final int frames;
        private float value;
        private float target;
        private float step;
        private int remaining;

        Ramp(float initial, int frames) {
            value = target = initial;
            this.frames = frames;
        }

        float next(float nextTarget) {
            if (Float.compare(target, nextTarget) != 0) {
                target = nextTarget;
                remaining = frames;
                step = (target - value) / frames;
            }
            if (remaining > 0) {
                value += step;
                if (--remaining == 0) value = target;
            }
            return value;
        }
    }

    private static final class LogRamp {
        private final int frames;
        private float value;
        private float target;
        private double ratio = 1.0;
        private int remaining;

        LogRamp(float initial, int frames) {
            value = target = initial;
            this.frames = frames;
        }

        float next(float nextTarget) {
            if (Float.compare(target, nextTarget) != 0) {
                target = nextTarget;
                remaining = frames;
                ratio = Math.pow(target / value, 1.0 / frames);
            }
            if (remaining > 0) {
                value *= ratio;
                if (--remaining == 0) value = target;
            }
            return value;
        }
    }
}
