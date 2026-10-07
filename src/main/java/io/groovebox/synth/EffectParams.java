package io.groovebox.synth;

/**
 * The knobs of the synth channel's two effects: a delay (which way its repeats travel, how long,
 * how much comes back, how dark each repeat gets, how much of it is heard) and a reverb (how big
 * the room is, how dark it is, how much of it is heard), the crusher in front of them (how far the
 * the channel's sample rate and bit depth are dropped, 0 for neither), the sidechain that ducks
 * what comes out under the kick (how deep it dips, how long it takes to come back), and the
 * amplifier in front of all of them (what shape the channel is driven into, how hard, and the level
 * it leaves at) and the filter between the crusher and the delay (which of the two, how far down and
 * how resonant, or none at all). At {@code drive} 0 the amplifier does nothing at all, which is where a synth leaves
 * it: a synth drives its own voices, so driving them again in the channel would be twice.
 * Like {@link SynthParams} this is a value the audio thread reads and a panel replaces whole.
 */
public record EffectParams(
        DelayMode delayMode, float delayMillis, float delayFeedback, float delayTone, float delayMix,
        float reverbSize, float reverbDamping, float reverbMix,
        float duckDepth, float duckMillis, float crush,
        Saturation shape, float drive, float outputTrim,
        ChannelFilter filter, float filterCutoffHz, float filterResonance,
        float filterEnvAmountHz, float filterAttackSeconds, float filterDecaySeconds,
        float filterSustainLevel, float filterReleaseSeconds) {

    /** Both effects present but quiet, no ducking: the sound is the patch's, with a little room around it. */
    public static final EffectParams DEFAULT =
            new EffectParams(DelayMode.PING_PONG, 250, 0.35f, 0.55f, 0.0f, 0.62f, 0.45f, 0.12f);

    /**
     * Nothing heard: both mixes down, no crusher and no ducking, with the rest of the settings where
     * they would be useful. What a recorded loop arrives as — it is already the sound it is, so the
     * channel is there to be reached for rather than to colour it from the start; turning one Mix up
     * gives a delay or a room that is already in a usable place.
     */
    public static final EffectParams DRY =
            new EffectParams(DelayMode.PING_PONG, 250, 0.35f, 0.55f, 0.0f, 0.62f, 0.45f, 0.0f);

    /**
     * Everything but the amplifier, which passes what it is given. A synth drives its own voices,
     * so this is the one its panel builds; only a track with no voices of its own - a loop - reaches
     * for the channel's drive.
     */
    public EffectParams(DelayMode delayMode, float delayMillis, float delayFeedback, float delayTone,
                        float delayMix, float reverbSize, float reverbDamping, float reverbMix,
                        float duckDepth, float duckMillis, float crush) {
        this(delayMode, delayMillis, delayFeedback, delayTone, delayMix, reverbSize, reverbDamping,
                reverbMix, duckDepth, duckMillis, crush, Saturation.DIODE, 0.0f, 1.0f,
                ChannelFilter.OFF, 1200.0f, 0.2f, 0.0f, 0.001f, 0.16f, 0.0f, 0.1f);
    }

    /** Delay and reverb only, with the sidechain and the crusher off. */
    public EffectParams(DelayMode delayMode, float delayMillis, float delayFeedback, float delayTone,
                        float delayMix, float reverbSize, float reverbDamping, float reverbMix) {
        this(delayMode, delayMillis, delayFeedback, delayTone, delayMix, reverbSize, reverbDamping,
                reverbMix, 0.0f, 250, 0.0f);
    }

    /** Everything but the crusher, which stays off. */
    public EffectParams(DelayMode delayMode, float delayMillis, float delayFeedback, float delayTone,
                        float delayMix, float reverbSize, float reverbDamping, float reverbMix,
                        float duckDepth, float duckMillis) {
        this(delayMode, delayMillis, delayFeedback, delayTone, delayMix, reverbSize, reverbDamping,
                reverbMix, duckDepth, duckMillis, 0.0f);
    }

    public EffectParams {
        delayMode = delayMode == null ? DelayMode.PING_PONG : delayMode;
        delayMillis = clamp(delayMillis, 10, 2000);
        delayFeedback = clamp(delayFeedback, 0, 0.95f);
        delayTone = clamp(delayTone, 0, 1);
        delayMix = clamp(delayMix, 0, 1);
        reverbSize = clamp(reverbSize, 0, 1);
        reverbDamping = clamp(reverbDamping, 0, 1);
        reverbMix = clamp(reverbMix, 0, 1);
        duckDepth = clamp(duckDepth, 0, 1);
        duckMillis = clamp(duckMillis, 20, 2000);
        crush = clamp(crush, 0, 1);
        shape = shape == null ? Saturation.DIODE : shape;
        // the same range the synth's own drive and output have, so a knob means the same on either
        drive = clamp(drive, 0, 2);
        outputTrim = clamp(outputTrim, 0, 1.5f);
        filter = filter == null ? ChannelFilter.OFF : filter;
        // the same floor and ceiling the voice's own filter and its knob have
        filterCutoffHz = clamp(filterCutoffHz, 40, 18000);
        filterResonance = clamp(filterResonance, 0, 0.95f);
        // how far the envelope lifts the cutoff, in hertz, and the shape it lifts it in. Zero hertz
        // is an envelope doing nothing, which is where a channel arrives and where a synth stays
        filterEnvAmountHz = clamp(filterEnvAmountHz, -12000, 12000);
        filterAttackSeconds = clamp(filterAttackSeconds, 0.001f, 2);
        filterDecaySeconds = clamp(filterDecaySeconds, 0.001f, 3);
        filterSustainLevel = clamp(filterSustainLevel, 0, 1);
        filterReleaseSeconds = clamp(filterReleaseSeconds, 0.001f, 4);
    }

    /** The same effects with the filter swept by an envelope struck on every hit into the channel. */
    public EffectParams sweeping(float amountHz, float attack, float decay, float sustain, float release) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, filterCutoffHz, filterResonance,
                amountHz, attack, decay, sustain, release);
    }

    /** The same effects heard through {@code filter}; {@link ChannelFilter#OFF} passes untouched. */
    public EffectParams filtered(ChannelFilter filter, float cutoffHz, float resonance) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, cutoffHz, resonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    /** The same effects driven into {@code shape} as hard as {@code drive} says, and trimmed after. */
    public EffectParams driven(Saturation shape, float drive, float outputTrim) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    public EffectParams withDuck(float depth, float millis) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, depth, millis, crush, shape, drive, outputTrim,
                filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    /** The same effects with the delay heard at {@code mix}: nothing at 0, a wash at 1. */
    public EffectParams withDelayMix(float mix) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone,
                Math.max(0.0f, Math.min(1.0f, mix)), reverbSize, reverbDamping, reverbMix,
                duckDepth, duckMillis, crush, shape, drive, outputTrim,
                filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    /** The same effects with a repeat every {@code millis}: what a delay locked to the beat is given. */
    public EffectParams withDelayMillis(float millis) {
        return new EffectParams(delayMode, millis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    /** The same effects with each repeat {@code feedback} as loud as the one before it. */
    public EffectParams withDelayFeedback(float feedback) {
        return new EffectParams(delayMode, delayMillis, feedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    /** The same effects in a room {@code size} big, from a booth at 0 to a hall at 1. */
    public EffectParams withReverbSize(float size) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                size, reverbDamping, reverbMix, duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    /** The same effects with the reverb heard at {@code mix}: nothing at 0, all room at 1. */
    public EffectParams withReverbMix(float mix) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, Math.max(0.0f, Math.min(1.0f, mix)), duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    public EffectParams withCrush(float crush) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, duckDepth, duckMillis, crush,
                shape, drive, outputTrim, filter, filterCutoffHz, filterResonance,
                filterEnvAmountHz, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
