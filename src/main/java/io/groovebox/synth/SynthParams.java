package io.groovebox.synth;

import static io.groovebox.synth.NovasawDsp.clamp;

/**
 * Everything {@link NovasawVoice} needs to know to make a sound: the seven unison saws and their
 * movement, the sine an octave below them, the filter and its own envelope, the level's envelope
 * and the drive. What a patch's constructor used to bake into its fields is now a value: stored in
 * a patch, read frame by frame by a voice, or replaced while a note is sounding when a knob moves.
 *
 * <p>{@code unisonGain} and {@code resonanceCompensation} follow from the others, so build these
 * with {@link #of}, which works them out; the canonical constructor is for {@code with...} copies.
 */
public record SynthParams(
        float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
        float detuneCents, float subLevel, float vibratoCents, float motionRateHz, float motion,
        float cutoffHz, float resonance, float filterEnvAmountHz, float keyTrackHzPerSemitone,
        float filterAttackSeconds, float filterDecaySeconds, float filterSustainLevel,
        float filterReleaseSeconds,
        float drive, float outputTrim, float unisonGain, float resonanceCompensation,
        Saturation shape, FilterKind filter, int unisonVoices, Waveform waveform) {

    public SynthParams {
        // Evenly spread unison phases nearly cancel a sine's only harmonic. A bass sine needs
        // one stable oscillator; keep the count and gain in agreement for every construction path.
        if (waveform == Waveform.SINE) {
            unisonVoices = 1;
            unisonGain = unisonGainFor(detuneCents, 1);
        }
    }

    /**
     * A patch whose filter follows the level's envelope, as every patch did before the filter had
     * one of its own: the filter's four stages are copies of the level's.
     */
    public static SynthParams of(
            float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
            float detuneCents, float subLevel, float vibratoCents, float motionRateHz, float motion,
            float cutoffHz, float resonance, float filterEnvAmountHz, float keyTrackHzPerSemitone,
            float drive, float outputTrim) {
        return of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                drive, outputTrim);
    }

    public static SynthParams of(
            float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
            float detuneCents, float subLevel, float vibratoCents, float motionRateHz, float motion,
            float cutoffHz, float resonance, float filterEnvAmountHz, float keyTrackHzPerSemitone,
            float filterAttackSeconds, float filterDecaySeconds, float filterSustainLevel,
            float filterReleaseSeconds,
            float drive, float outputTrim) {
        float unisonGain = unisonGainFor(detuneCents, NovasawVoice.UNISON_VOICES);
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                // forty, the same floor the knob and both filters have. It was eighty here as well
                // as in the voice, so the knob's bottom octave asked for something it never got
                clamp(cutoffHz, NovasawVoice.CUTOFF_FLOOR, 18000.0f), resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, 1.0f / (1.0f + resonance * 0.38f),
                Saturation.DIODE, FilterKind.TWO_POLE, NovasawVoice.UNISON_VOICES, Waveform.SAW);
    }

    /**
     * What {@code of} cannot work out from its arguments, put back on a patch it has just rebuilt.
     * Every {@code with*} goes through {@code of}, which makes a patch of the default shape and the
     * default filter; without this, turning the cutoff would quietly reset both. One place, so the
     * next thing added to a patch has one place to be added to.
     */
    private SynthParams keeping(SynthParams rebuilt) {
        return rebuilt.withShape(shape).withFilter(filter).withVoices(unisonVoices).withWaveform(waveform);
    }

    /**
     * How much the saws are turned down for being several: they sum, so the more of them there are
     * the quieter each must be. The square root is the sum of things that do not quite line up, and
     * the detune term nudges it because at a wide detune they line up less than at a narrow one.
     */
    private static float unisonGainFor(float detuneCents, int voices) {
        float correlation = clamp(detuneCents / 30.0f, 0.0f, 1.0f);
        float atSeven = (0.82f + correlation * 0.18f) / (float) Math.sqrt(NovasawVoice.UNISON_VOICES);
        // seven saws are not seven times a saw, nor even the root of seven: spread evenly across the
        // cycle they cancel as much as they add, and how much depends on how far apart they are
        // tuned. Measured on the patches - at 1.7 cents the seven are only 2.2 dB above one, at 16
        // they are 6.0 - and fitted; at seven voices this comes to exactly the gain it always was,
        // so no patch changes.
        float together = clamp(0.12f + 0.0145f * detuneCents, 0.0f, 0.5f);
        return atSeven * (float) Math.pow((double) NovasawVoice.UNISON_VOICES / Math.max(1, voices), together);
    }

    /**
     * The same patch with {@code next} oscillators instead of seven. A sine always uses one.
     * Seven is a lead; a bass wants one to three, and one is the only honest number for an acid line.
     * The gain that holds them together
     * is worked out again for the new count, so the patch does not change loudness with it.
     */
    public SynthParams withVoices(int next) {
        int voices = Math.max(1, Math.min(NovasawVoice.UNISON_VOICES, next));
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGainFor(detuneCents, voices), resonanceCompensation,
                shape, filter, voices, waveform);
    }

    /**
     * The same patch with its unison spread {@code cents} wide, and the gain that holds the voices
     * together worked out again for it, as for a new count.
     */
    public SynthParams withDetune(float cents) {
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                cents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGainFor(cents, unisonVoices), resonanceCompensation,
                shape, filter, unisonVoices, waveform);
    }

    /** The same patch holding a note at {@code level} of its peak once the decay is done. */
    public SynthParams withSustain(float level) {
        return new SynthParams(attackSeconds, decaySeconds, Math.max(0, Math.min(1, level)), releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, resonanceCompensation,
                shape, filter, unisonVoices, waveform);
    }

    /** The same patch dying away over {@code seconds} once a note is let go of. */
    public SynthParams withRelease(float seconds) {
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, Math.max(0.001f, seconds),
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, resonanceCompensation,
                shape, filter, unisonVoices, waveform);
    }

    /** The same patch on another waveform: a saw, a square or a single-oscillator sine. */
    public SynthParams withWaveform(Waveform next) {
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, resonanceCompensation, shape, filter, unisonVoices, next);
    }

    /** The same patch through the other filter: two poles or four. */
    public SynthParams withFilter(FilterKind next) {
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, resonanceCompensation, shape, next, unisonVoices, waveform);
    }

    /** The same patch driven into another shape: the drive says how hard, this says what kind. */
    public SynthParams withShape(Saturation next) {
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, resonanceCompensation, next, filter, unisonVoices, waveform);
    }

    public SynthParams withCutoff(float hz) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, hz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, drive, outputTrim));
    }

    public SynthParams withDrive(float amount) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, cutoffHz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, amount, outputTrim));
    }

    public SynthParams withResonance(float amount) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, cutoffHz, amount, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, drive, outputTrim));
    }

    public SynthParams withSub(float level) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, level,
                vibratoCents, motionRateHz, motion, cutoffHz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, drive, outputTrim));
    }

    /** The same patch with its filter swept {@code hz} by its envelope: up, or down when negative. */
    public SynthParams withFilterEnvAmount(float hz) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, cutoffHz, resonance, hz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, drive, outputTrim));
    }

    /** The same patch with the filter's envelope set apart from the level's. */
    public SynthParams withFilterEnvelope(float attack, float decay, float sustain, float release) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, cutoffHz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, attack, decay, sustain, release, drive, outputTrim));
    }
}
