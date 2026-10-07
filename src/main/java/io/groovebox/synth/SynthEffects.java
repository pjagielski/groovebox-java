package io.groovebox.synth;

import io.groovebox.audio.AudioEffect;

import java.util.function.Supplier;

import static io.groovebox.synth.NovasawDsp.clamp;

/**
 * A channel's effects in stereo: an amplifier that drives what it is given into one of
 * {@link Saturation}'s shapes, then a crusher, then whichever of two filters it was given — or
 * neither — then a ping-pong delay with a damped feedback path, then a Freeverb-style reverb.
 *
 * <p>Two channels in as well as out. A synth voice is the same in both ears and comes out of here
 * wide; anything that arrives wide — a sample played into the channel — stays as wide as it came.
 *
 * <p>The reverb follows Jezar's Freeverb (public domain, June 2000) as described in Julius O.
 * Smith's <i>Physical Audio Signal Processing</i>, §3.6: eight lowpass-feedback comb filters in
 * parallel into four allpass sections in series, per channel, with the right channel's twelve delay
 * lines lengthened by {@code STEREO_SPREAD} so the two ears never hear the same room. The tunings
 * below are Freeverb's own, scaled from its 44.1 kHz assumption to whatever rate we run at:
 * {@code damping = damp * 0.4}, {@code feedback = size * 0.28 + 0.7}, allpass {@code g = 0.5}.
 *
 * <p>The delay has four ways of using its two lines — see {@link DelayMode}. Whichever it is, each
 * trip round the loop goes through a one-pole lowpass (the Tone knob) and a soft clip, which is what
 * keeps a high feedback setting from either turning into a bright screech or running away.
 *
 * <p>Last comes the sidechain: on each {@link #duck()} — a kick — the whole channel, echoes and room
 * included, drops by the Duck depth and comes back along a half cosine over the Duck release. The
 * curve starts flat, so the dip holds for a moment before it swells back, which is what makes the
 * synth pump rather than merely get quieter. A millisecond or two of smoothing on the gain keeps
 * the drop and a kick landing mid-swell from clicking.
 *
 * <p>Like the voices, everything is read from {@link EffectParams} on every frame, so a knob is
 * heard in the tail that is already ringing.
 */
public final class SynthEffects implements AudioEffect {

    private static final int[] COMB_FRAMES = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS_FRAMES = {556, 441, 341, 225};
    private static final int STEREO_SPREAD = 23;
    private static final float FIXED_GAIN = 0.015f;
    private static final float WET_SCALE = 3.0f;
    private static final float DAMP_SCALE = 0.4f;
    private static final float ROOM_SCALE = 0.28f;
    private static final float ROOM_OFFSET = 0.7f;
    private static final float ALLPASS_FEEDBACK = 0.5f;

    private static final int CHANNELS = 2;
    private static final float TIME_SMOOTHING = 0.0005f;
    private static final float HIGHPASS_COEFFICIENT = 0.995f;
    private static final double DUCK_SMOOTHING_SECONDS = 0.0015;

    private final Supplier<EffectParams> params;
    private final int sampleRate;

    private final float[][] delayLines = new float[CHANNELS][];
    private final int[] delayPositions = new int[CHANNELS];
    private final float[] toneStates = new float[CHANNELS];
    private final float[] highpassStates = new float[CHANNELS];
    private final float[] highpassInputs = new float[CHANNELS];

    private final float[][][] combs = new float[CHANNELS][COMB_FRAMES.length][];
    private final float[][] combStores = new float[CHANNELS][COMB_FRAMES.length];
    private final int[][] combPositions = new int[CHANNELS][COMB_FRAMES.length];
    private final float[][][] allpasses = new float[CHANNELS][ALLPASS_FRAMES.length][];
    private final int[][] allpassPositions = new int[CHANNELS][ALLPASS_FRAMES.length];

    private final float duckSmoothing;

    private float delayFrames;
    private double tapePhase;
    private long framesSinceKick = Long.MAX_VALUE / 2;
    private float duckGain = 1.0f;
    private double crushPhase = 1;
    private final float[] crushHeld = new float[CHANNELS];
    private final float[] crushed = new float[CHANNELS];
    private float cachedCrush = Float.NaN;
    private final NovasawDsp.LowpassFilter[] twoPole =
            {new NovasawDsp.LowpassFilter(), new NovasawDsp.LowpassFilter()};
    private final DiodeLadder[] ladders = {new DiodeLadder(), new DiodeLadder()};
    // where the filter is on its way to being heard, and which of the two it is on its way to being
    private float heard;
    private float ladderBlend;
    private final NovasawDsp.Adsr filterEnvelope = new NovasawDsp.Adsr();
    // where the envelope is since it was last struck, in frames; held for as long as one hit lasts
    private int sinceStruck = Integer.MAX_VALUE / 2;
    private int heldFrames;
    private float smoothedCutoff = 1200;
    private float smoothedResonance;
    private final float filterGlide;
    private final float knobGlide;
    private double crushStep;
    private double crushLevels;

    public SynthEffects(int sampleRate, Supplier<EffectParams> params) {
        this.sampleRate = sampleRate;
        this.params = params;
        this.delayFrames = params.get().delayMillis() * sampleRate / 1000.0f;
        this.duckSmoothing = (float) (1 - Math.exp(-1 / (DUCK_SMOOTHING_SECONDS * sampleRate)));
        // eight milliseconds to cross between filters or in and out of one, ten for a knob being
        // turned: long enough that neither steps, short enough that neither feels late
        this.filterGlide = 1.0f / Math.max(1, Math.round(sampleRate * 0.008f));
        this.knobGlide = (float) (1 - Math.exp(-1 / (0.010 * sampleRate)));
        for (int channel = 0; channel < CHANNELS; channel++) {
            delayLines[channel] = new float[2 * sampleRate + 1];
            int spread = channel * STEREO_SPREAD;
            for (int comb = 0; comb < COMB_FRAMES.length; comb++) {
                combs[channel][comb] = new float[scaled(COMB_FRAMES[comb] + spread)];
            }
            for (int allpass = 0; allpass < ALLPASS_FRAMES.length; allpass++) {
                allpasses[channel][allpass] = new float[scaled(ALLPASS_FRAMES[allpass] + spread)];
            }
        }
    }

    /** Freeverb's lengths are in frames at 44.1 kHz; at another rate the room would change size. */
    private int scaled(int framesAt44k) {
        return Math.max(1, Math.round(framesAt44k * sampleRate / 44_100.0f));
    }

    @Override
    public void process(float left, float right, float[] stereoOut) {
        EffectParams current = params.get();
        crush(driven(left, current), driven(right, current), current);
        filter(current);
        delay(crushed[0], crushed[1], current, stereoOut);
        for (int channel = 0; channel < CHANNELS; channel++) {
            stereoOut[channel] = reverb(channel, stereoOut[channel], current);
        }
        float gain = ducked(current);
        stereoOut[0] *= gain;
        stereoOut[1] *= gain;
    }

    /**
     * The channel driven, before anything else hears it, so the echoes and the room are of the
     * driven sound rather than of the clean one — which is the order a desk puts them in, and the
     * order the synth's own voices already meet: driven, then sent.
     *
     * <p>At drive 0 the shaper is not called at all. It is not transparent there: every one of these
     * curves bends what it is given from the first sample, and a channel with its drive down has to
     * pass what it was handed exactly. The trim is applied either way, and is 1 unless asked for.
     */
    private static float driven(float input, EffectParams current) {
        float drive = current.drive();
        float shaped = drive <= 0.0f ? input : current.shape().levelled(input, drive);
        return shaped * current.outputTrim();
    }

    /**
     * The channel through whichever filter it was given, between the crusher and the delay: what is
     * echoed and put in a room is what the filter left, which is the order a desk puts them in and
     * the only one where turning the cutoff down makes the repeats darker too.
     *
     * <p>Both filters run whatever is chosen, and what changes is how much of each is heard. That
     * costs a few multiplies and buys three things: no state to reset, no filter starting cold, and
     * no click on a switch — the two cross over a few milliseconds, as does the whole filter coming
     * in and out, since {@link ChannelFilter#OFF} is a hand reaching for the bypass mid-take.
     */
    @Override
    public void strike() {
        // struck again from the top, as a note retriggers a synth's own filter envelope. A break is
        // struck far more often than a line is played, so this is the common case rather than the rare
        sinceStruck = 0;
        filterEnvelope.reset();
    }

    private void filter(EffectParams current) {
        boolean on = current.filter() != ChannelFilter.OFF;
        heard = clamp(heard + (on ? filterGlide : -filterGlide), 0, 1);
        ladderBlend = clamp(ladderBlend
                + (current.filter() == ChannelFilter.LADDER ? filterGlide : -filterGlide), 0, 1);
        // the cutoff glides where it is heard, which is by ratio: half way between 100 and 10000 is
        // a thousand, not five thousand
        smoothedCutoff *= (float) Math.pow(current.filterCutoffHz() / smoothedCutoff, knobGlide);
        smoothedResonance += (current.filterResonance() - smoothedResonance) * knobGlide;
        // held for the whole of the decay and the sustain, so what is struck stays open until the
        // next thing is struck: a slice has no length of its own the way a note does
        heldFrames = Math.max(1, Math.round((current.filterAttackSeconds()
                + current.filterDecaySeconds()) * sampleRate));
        float lift = filterEnvelope.next(sinceStruck, heldFrames, current.filterAttackSeconds(),
                current.filterDecaySeconds(), current.filterSustainLevel(),
                current.filterReleaseSeconds(), sampleRate);
        sinceStruck++;
        float swept = clamp(smoothedCutoff + lift * current.filterEnvAmountHz(), 40, 18000);
        for (int channel = 0; channel < CHANNELS; channel++) {
            float dry = crushed[channel];
            // run both even at rest, so neither is ever asked to start from silence mid-note
            float flat = twoPole[channel].process(dry, swept, smoothedResonance, sampleRate);
            float steep = ladders[channel].process(dry, swept, smoothedResonance, sampleRate);
            float wet = flat + (steep - flat) * ladderBlend;
            crushed[channel] = dry + (wet - dry) * heard;
        }
    }

    /**
     * The channel through a cheap converter before anything else: held at a lower rate and rounded
     * to fewer bits, so the delay and the reverb work on what the crusher left. Nothing filters the
     * held signal, which is where the grit comes from. At 0 the channel passes untouched.
     *
     * <p>Both ears are held at the same instant, off one phase, because a converter has one clock:
     * sampling them apart would move one side in time against the other and turn the grit into a
     * wobble across the image.
     */
    private void crush(float left, float right, EffectParams current) {
        float crush = current.crush();
        if (crush <= 0) {
            crushPhase = 1;
            crushed[0] = left;
            crushed[1] = right;
            return;
        }
        // 22 kHz and 16 bits at the bottom of the knob, about 700 Hz and 4 bits at the top
        if (Float.compare(cachedCrush, crush) != 0) {
            cachedCrush = crush;
            crushStep = 700 * Math.pow(31.5, 1 - crush) / sampleRate;
            crushLevels = Math.pow(2, 16 - 12 * crush - 1);
        }
        crushPhase += crushStep;
        if (crushPhase >= 1) {
            crushPhase -= Math.floor(crushPhase);
            // rounding to the nearest keeps a level at zero, so a silent channel stays silent
            crushHeld[0] = (float) (Math.round(left * crushLevels) / crushLevels);
            crushHeld[1] = (float) (Math.round(right * crushLevels) / crushLevels);
        }
        crushed[0] = crushHeld[0];
        crushed[1] = crushHeld[1];
    }

    @Override
    public void duck() {
        framesSinceKick = 0;
    }

    /** How loud the channel is this frame, somewhere between 1 and 1 − depth, following the last kick. */
    private float ducked(EffectParams current) {
        float target = 1.0f;
        float releaseFrames = current.duckMillis() * sampleRate / 1000.0f;
        if (current.duckDepth() > 0 && framesSinceKick < releaseFrames) {
            double swell = 0.5 * (1 + Math.cos(Math.PI * framesSinceKick / releaseFrames));
            target = (float) (1 - current.duckDepth() * swell);
        }
        framesSinceKick++;
        duckGain += (target - duckGain) * duckSmoothing;
        return duckGain;
    }

    private void delay(float left, float right, EffectParams current, float[] stereoOut) {
        float target = current.delayMillis() * sampleRate / 1000.0f;
        delayFrames += (target - delayFrames) * TIME_SMOOTHING;
        tapePhase += 2 * Math.PI * DelayMode.TAPE_RATE_HZ / sampleRate;
        if (tapePhase > 2 * Math.PI) {
            tapePhase -= 2 * Math.PI;
        }

        float leftFrames = framesFor(current.delayMode(), 0);
        float rightFrames = framesFor(current.delayMode(), 1);
        float echoLeft = read(0, leftFrames);
        float echoRight = read(1, rightFrames);

        switch (current.delayMode()) {
            // the dry signal enters the left line only and the two lines' feedback is crossed over,
            // so a repeat is heard first on one side and then on the other. Both ears go into that
            // one line, summed: sending each ear to a line of its own would make the repeats travel
            // outwards from where they started instead of crossing, which is a stereo delay and not
            // this one
            case PING_PONG -> {
                float summed = (left + right) * 0.5f;
                write(0, summed + loop(0, echoRight, current));
                write(1, loop(1, echoLeft, current));
            }
            // a line each, feeding itself: same time in MONO and TAPE, two thirds of it in STEREO
            case MONO, STEREO, TAPE -> {
                write(0, left + loop(0, echoLeft, current));
                write(1, right + loop(1, echoRight, current));
            }
        }

        float mix = current.delayMix();
        stereoOut[0] = left * (1 - mix) + echoLeft * mix;
        stereoOut[1] = right * (1 - mix) + echoRight * mix;
    }

    /** How far back each line reads: the same for both, shorter on the right, or drifting. */
    private float framesFor(DelayMode mode, int channel) {
        return switch (mode) {
            case MONO, PING_PONG -> delayFrames;
            case STEREO -> channel == 0 ? delayFrames : delayFrames * DelayMode.STEREO_RATIO;
            // the two sides drift in opposite directions, which widens the repeats as they warble
            case TAPE -> delayFrames * (float) (1 + DelayMode.TAPE_DEPTH
                    * Math.sin(tapePhase + (channel == 0 ? 0 : Math.PI / 2)));
        };
    }

    /** One trip round the feedback loop: quieter, darker, without the rumble, and never louder than 1. */
    private float loop(int channel, float echo, EffectParams current) {
        float fed = echo * current.delayFeedback();
        float cutoff = 0.05f + current.delayTone() * 0.94f;
        toneStates[channel] += (fed - toneStates[channel]) * cutoff;
        float damped = toneStates[channel];
        float highpassed = HIGHPASS_COEFFICIENT * (highpassStates[channel] + damped - highpassInputs[channel]);
        highpassInputs[channel] = damped;
        highpassStates[channel] = highpassed;
        return softClip(highpassed);
    }

    private static float softClip(float value) {
        return (float) Math.tanh(value);
    }

    private float read(int channel, float frames) {
        float[] line = delayLines[channel];
        float read = delayPositions[channel] - Math.min(frames, line.length - 2);
        while (read < 0) {
            read += line.length;
        }
        int first = (int) read;
        float fraction = read - first;
        return line[first] * (1 - fraction) + line[(first + 1) % line.length] * fraction;
    }

    private void write(int channel, float value) {
        float[] line = delayLines[channel];
        line[delayPositions[channel]] = value;
        delayPositions[channel] = (delayPositions[channel] + 1) % line.length;
    }

    /** Freeverb, one channel of it: eight damped combs in parallel, then four allpasses in series. */
    private float reverb(int channel, float input, EffectParams current) {
        float feedback = current.reverbSize() * ROOM_SCALE + ROOM_OFFSET;
        float damping = current.reverbDamping() * DAMP_SCALE;
        float fed = input * FIXED_GAIN;
        float wet = 0.0f;

        for (int comb = 0; comb < COMB_FRAMES.length; comb++) {
            float[] line = combs[channel][comb];
            int position = combPositions[channel][comb];
            float heard = line[position];
            combStores[channel][comb] = heard * (1 - damping) + combStores[channel][comb] * damping;
            line[position] = fed + combStores[channel][comb] * feedback;
            combPositions[channel][comb] = (position + 1) % line.length;
            wet += heard;
        }
        for (int allpass = 0; allpass < ALLPASS_FRAMES.length; allpass++) {
            float[] line = allpasses[channel][allpass];
            int position = allpassPositions[channel][allpass];
            float heard = line[position];
            line[position] = wet + heard * ALLPASS_FEEDBACK;
            allpassPositions[channel][allpass] = (position + 1) % line.length;
            wet = heard - wet;
        }
        return input * (1 - current.reverbMix()) + wet * WET_SCALE * current.reverbMix();
    }
}
