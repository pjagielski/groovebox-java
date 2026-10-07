package io.groovebox.audio;

import io.groovebox.synth.NovasawDsp;

import java.util.Arrays;

/**
 * The effects played over the whole mix, drums and synth together, as a sampler's Perform screen
 * plays them: each one off until a finger holds it, then set by where the finger is. The setters
 * are safe from any thread; {@link #process} runs on the render thread, block by block.
 *
 * <p>A position is 0 at the bottom of a strip and 1 at the top; anything below 0 turns the effect
 * off. Positions glide rather than jump, and an effect fades in and out over a few milliseconds,
 * so neither a drag nor letting go clicks.
 */
public final class PerformanceFx {
    private static final double GLIDE_SECONDS = 0.015;
    private static final double FADE_SECONDS = 0.005;
    private static final double OFF = -1;

    private final Crush crush;
    private final Filter filter;
    private final Talkbox talkbox;
    private final Dirty dirty;
    private final Dub dub;

    PerformanceFx(int sampleRate) {
        crush = new Crush(sampleRate);
        filter = new Filter(sampleRate);
        talkbox = new Talkbox(sampleRate);
        dirty = new Dirty(sampleRate);
        dub = new Dub(sampleRate);
    }

    /**
     * Crush: the mix at a lower sample rate and fewer bits, a little clipped. The top of the strip
     * crushes most (about 700 Hz and 4 bits), the bottom least (about 11 kHz and 10 bits).
     */
    public void crush(double position) {
        crush.target = position;
    }

    /**
     * Filter: a resonant low-pass below the centre, closing towards the bottom, and a high-pass
     * above it, opening towards the top. At the centre the mix passes as it is.
     */
    public void filter(double position) {
        filter.target = position;
    }

    /**
     * Talkbox: the mix sung through a vowel, a — e — i — o — u from the bottom of the strip to the
     * top, gliding from one to the next in between.
     */
    public void talkbox(double position) {
        talkbox.target = position;
    }

    /**
     * Dirty: the mix driven into the same diode shaper the synth patches overdrive through, harder
     * towards the top of the strip.
     */
    public void dirty(double position) {
        dirty.target = position;
    }

    /**
     * Dub: a ping-pong delay a dotted eighth long, in time with the jam, fed harder and louder
     * towards the top of the strip until it is not far off running away. Letting go stops feeding
     * it, and what is in it repeats its way out rather than being cut off.
     */
    public void dub(double position) {
        dub.target = position;
    }

    /** Lets every effect go, as Stop does. */
    public void releaseAll() {
        crush(OFF);
        filter(OFF);
        talkbox(OFF);
        dirty(OFF);
        dub(OFF);
    }

    /** The first {@code frames} stereo frames of {@code mix}, in place, with the jam at {@code bpm}. */
    void process(float[] mix, int frames, double bpm) {
        talkbox.process(mix, frames);
        dirty.process(mix, frames);
        crush.process(mix, frames);
        filter.process(mix, frames);
        dub.inTimeWith(bpm);
        dub.process(mix, frames);
    }

    /** What every effect shares: a position that glides, and a level that fades the effect in and out. */
    private abstract static class Effect {
        final int sampleRate;
        private final double glide;
        private final double fade;
        volatile double target = OFF;
        double position = 0.5;
        // 1 while the finger is down, 0 once it is off, and between the two while it fades
        double wet;

        Effect(int sampleRate) {
            this.sampleRate = sampleRate;
            glide = 1 - Math.exp(-1 / (GLIDE_SECONDS * sampleRate));
            fade = 1.0 / (FADE_SECONDS * sampleRate);
        }

        final void process(float[] mix, int frames) {
            double wanted = target;
            boolean on = wanted >= 0;
            if (!on && wet == 0 && !ringing()) {
                return;
            }
            if (on && wet == 0) {
                // coming in from off: start where the finger is, not glide there from the last place
                position = Math.min(1, wanted);
                reset();
            }
            for (int frame = 0; frame < frames; frame++) {
                if (on) {
                    position += (Math.min(1, wanted) - position) * glide;
                    wet = Math.min(1, wet + fade);
                } else {
                    wet = Math.max(0, wet - fade);
                }
                int left = frame * 2;
                float dryLeft = mix[left];
                float dryRight = mix[left + 1];
                apply(mix, left);
                if (wet == 1 || !blends()) {
                    continue;
                }
                mix[left] = (float) (dryLeft + (mix[left] - dryLeft) * wet);
                mix[left + 1] = (float) (dryRight + (mix[left + 1] - dryRight) * wet);
            }
        }

        /** Whether the effect is still sounding although the finger is off it: a delay's repeats. */
        boolean ringing() {
            return false;
        }

        /**
         * Whether the fading in and out is left to the mixing here. An effect that adds to the mix
         * rather than replacing it - a delay, whose repeats have to ring on after the finger is
         * off - says no and keeps its own balance.
         */
        boolean blends() {
            return true;
        }

        /** Clears whatever the effect remembers, before it comes in again. */
        abstract void reset();

        /** Replaces the stereo frame at {@code mix[left]}, {@code mix[left + 1]} with the effect's output. */
        abstract void apply(float[] mix, int left);
    }

    /**
     * A bitcrusher, as a sampler's is: the mix through a transistor stage, then held and rounded as
     * a cheap converter would. Nothing filters the held signal, which is the point — the frequencies
     * that fold back over the Nyquist of the rate it pretends to run at are where the grit comes
     * from, and a proper anti-aliasing filter would take exactly that away.
     */
    private static final class Crush extends Effect {
        // the stage in front, which a sampler's crusher has and which gives the rounding more to bite on
        private static final double DRIVE = 1.8;
        private static final double TRIM = 0.75;

        private double phase = 1;
        private float heldLeft;
        private float heldRight;

        Crush(int sampleRate) {
            super(sampleRate);
        }

        @Override
        void reset() {
            phase = 1;
        }

        @Override
        void apply(float[] mix, int left) {
            // about 11 kHz at the bottom of the strip and 700 Hz at the top, 10 bits down to 4
            double gentle = 1 - position;
            double rate = 700 * Math.pow(16, gentle);
            phase += rate / sampleRate;
            if (phase >= 1) {
                phase -= Math.floor(phase);
                double steps = Math.pow(2, 4 + 6 * gentle - 1);
                heldLeft = crushed(mix[left], steps);
                heldRight = crushed(mix[left + 1], steps);
            }
            mix[left] = heldLeft;
            mix[left + 1] = heldRight;
        }

        /**
         * Driven into a soft clip, then rounded to {@code steps} levels a side. Rounding to the
         * nearest keeps a level at zero, so silence stays silent; rounding to the middle of each
         * step, as some crushers do, would leave half a step of hum under a quiet passage.
         */
        private static float crushed(float value, double steps) {
            double driven = Math.tanh(value * DRIVE);
            return (float) (Math.round(driven * steps) / steps * TRIM);
        }
    }

    /**
     * A state-variable filter in the trapezoidal form, which stays stable while its cutoff moves a
     * sample at a time — needed, since a finger sweeps it.
     */
    private static final class Filter extends Effect {
        private static final double RESONANCE = 0.75;
        private final double[] low = new double[2];
        private final double[] band = new double[2];

        Filter(int sampleRate) {
            super(sampleRate);
        }

        @Override
        void reset() {
            low[0] = low[1] = band[0] = band[1] = 0;
        }

        @Override
        void apply(float[] mix, int left) {
            boolean highPass = position > 0.5;
            double sweep = highPass ? (position - 0.5) * 2 : position * 2;
            double cutoff = highPass ? 20 * Math.pow(400, sweep) : 150 * Math.pow(120, sweep);
            double g = Math.tan(Math.PI * Math.min(cutoff, sampleRate * 0.45) / sampleRate);
            // no resonance at the centre, where the filter should be heard as nothing at all
            double k = 2 - 2 * RESONANCE * Math.min(1, Math.abs(position - 0.5) * 8);
            double a1 = 1 / (1 + g * (g + k));
            for (int channel = 0; channel < 2; channel++) {
                double input = mix[left + channel];
                double v1 = a1 * (band[channel] + g * (input - low[channel]));
                double v2 = low[channel] + g * v1;
                band[channel] = 2 * v1 - band[channel];
                low[channel] = 2 * v2 - low[channel];
                double high = input - k * v1 - v2;
                mix[left + channel] = (float) (highPass ? high : v2);
            }
        }
    }

    /**
     * A formant filter, the way a talkbox works without the tube: the peaks a mouth shape puts into
     * a voice, put into the mix instead. Five resonant band-passes in parallel, one per formant,
     * their frequencies, levels and widths a tenor's, from Csound's formant tables; between two
     * vowels each band is taken part of the way from one to the other, frequencies on a log scale.
     *
     * <p>Three details are what make it speak rather than whistle. Adjacent bands are summed with
     * opposite signs, as Klatt's parallel synthesizer does, or their skirts cancel and notch the
     * response where the vowel should be. The mix is driven into a soft clip on the way in, as the
     * Csound Journal's talk-box does and as a real one's compression driver cannot help doing: a
     * band gives back nothing the sound did not bring it, and a richer sound gives the formants
     * more to work with. And the bands are widened, since a mix is not the buzz of vocal cords that
     * a voice's own narrow formants are cut for.
     */
    private static final class Talkbox extends Effect {
        // a, e, i, o, u: F1-F5 in Hz, their levels in dB and their widths in Hz (Csound, tenor)
        private static final double[][] FREQUENCIES = {
                {650, 1080, 2650, 2900, 3250},
                {400, 1700, 2600, 3200, 3580},
                {290, 1870, 2800, 3250, 3540},
                {400, 800, 2600, 2800, 3000},
                {350, 600, 2700, 2900, 3300}};
        private static final double[][] LEVELS = {
                {0, -6, -7, -8, -22},
                {0, -14, -12, -14, -20},
                {0, -15, -18, -20, -30},
                {0, -10, -12, -12, -26},
                {0, -20, -17, -14, -26}};
        private static final double[][] WIDTHS = {
                {80, 90, 120, 130, 140},
                {70, 80, 100, 120, 120},
                {40, 90, 100, 120, 120},
                {70, 80, 100, 130, 135},
                {40, 60, 100, 120, 120}};
        private static final int FORMANTS = 5;
        // a voice's formants are as narrow as they are because what goes through them is a buzz rich
        // in harmonics; a whole mix through bands that narrow whistles, and little is left of it
        private static final double WIDTH_SCALE = 2.2;
        // the upper formants sit 10-25 dB under the first, which over a mix loses them; half the
        // difference keeps the vowels apart
        private static final double LEVEL_SCALE = 0.5;
        private static final double DRIVE = 2.5;
        // what the bands keep of a driven mix, brought back to about the level it came in at
        private static final double MAKEUP = 3.2;

        private final double[][] low = new double[FORMANTS][2];
        private final double[][] band = new double[FORMANTS][2];
        private final double[] g = new double[FORMANTS];
        private final double[] k = new double[FORMANTS];
        private final double[] gain = new double[FORMANTS];
        private double shapedFor = Double.NaN;

        Talkbox(int sampleRate) {
            super(sampleRate);
        }

        @Override
        void reset() {
            for (int formant = 0; formant < FORMANTS; formant++) {
                low[formant][0] = low[formant][1] = band[formant][0] = band[formant][1] = 0;
            }
        }

        @Override
        void apply(float[] mix, int left) {
            if (position != shapedFor) {
                shape(position);
            }
            for (int channel = 0; channel < 2; channel++) {
                double input = Math.tanh(mix[left + channel] * DRIVE);
                double sum = 0;
                for (int formant = 0; formant < FORMANTS; formant++) {
                    double a1 = 1 / (1 + g[formant] * (g[formant] + k[formant]));
                    double v1 = a1 * (band[formant][channel] + g[formant] * (input - low[formant][channel]));
                    double v2 = low[formant][channel] + g[formant] * v1;
                    band[formant][channel] = 2 * v1 - band[formant][channel];
                    low[formant][channel] = 2 * v2 - low[formant][channel];
                    // k times the band output peaks at 1, whatever the width; every other one is
                    // subtracted rather than added, so neighbouring skirts do not cancel
                    sum += gain[formant] * k[formant] * v1;
                }
                mix[left + channel] = (float) Math.tanh(sum * MAKEUP);
            }
        }

        /** The five bands for {@code position}, part of the way from one vowel to the next. */
        private void shape(double position) {
            double along = Math.max(0, Math.min(1, position)) * (FREQUENCIES.length - 1);
            int from = Math.min(FREQUENCIES.length - 2, (int) along);
            double part = along - from;
            for (int formant = 0; formant < FORMANTS; formant++) {
                double hz = FREQUENCIES[from][formant]
                        * Math.pow(FREQUENCIES[from + 1][formant] / FREQUENCIES[from][formant], part);
                double db = LEVELS[from][formant] + (LEVELS[from + 1][formant] - LEVELS[from][formant]) * part;
                double width = WIDTHS[from][formant] + (WIDTHS[from + 1][formant] - WIDTHS[from][formant]) * part;
                g[formant] = Math.tan(Math.PI * Math.min(hz, sampleRate * 0.45) / sampleRate);
                k[formant] = width * WIDTH_SCALE / hz;
                gain[formant] = Math.pow(10, db * LEVEL_SCALE / 20) * (formant % 2 == 0 ? 1 : -1);
            }
            shapedFor = position;
        }
    }

    /** The diode shaper the synth patches overdrive through, over the whole mix instead. */
    private static final class Dirty extends Effect {

        Dirty(int sampleRate) {
            super(sampleRate);
        }

        @Override
        void reset() {
        }

        @Override
        void apply(float[] mix, int left) {
            // the levelled shaper: the grit climbs with the strip, the level stays where it was
            float drive = (float) (0.05 + 0.5 * position);
            mix[left] = NovasawDsp.shapeDiodeLevelled(mix[left], drive);
            mix[left + 1] = NovasawDsp.shapeDiodeLevelled(mix[left + 1], drive);
        }
    }

    /**
     * A ping-pong delay over the whole mix, in time with the jam: what goes in on the left comes
     * back on the right, and again on the left, quieter and darker each time, as a delay's repeats
     * lose their top. The time stays a dotted eighth and the strip feeds it, up to the edge of
     * running away.
     *
     * <p>It adds to the mix rather than replacing it, and keeps its own balance, so that
     * letting go only stops what goes in: the repeats already in the line ring their way out.
     */
    private static final class Dub extends Effect {
        private static final double DELAY_BEATS = 0.75;
        private static final double MAX_SECONDS = 2;
        private static final double GLIDE_SECONDS = 0.05;
        private static final double DAMPING_HZ = 3_000;
        private static final double QUIET = 1e-4;

        private final float[] lineLeft;
        private final float[] lineRight;
        private final double glide;
        private final double damping;
        private int writeAt;
        private double delayFrames;
        private double wantedDelayFrames;
        private double dampedLeft;
        private double dampedRight;
        private double loudest;
        private double bpm = 120;

        Dub(int sampleRate) {
            super(sampleRate);
            int frames = (int) (MAX_SECONDS * sampleRate) + 2;
            lineLeft = new float[frames];
            lineRight = new float[frames];
            glide = 1 - Math.exp(-1 / (GLIDE_SECONDS * sampleRate));
            damping = 1 - Math.exp(-2 * Math.PI * DAMPING_HZ / sampleRate);
            delayFrames = wantedDelayFrames = Math.min(frames - 2, sampleRate / 2.0);
        }

        /** The tempo its repeats are to land on the grid of. */
        void inTimeWith(double bpm) {
            this.bpm = bpm;
        }

        /** The delay's length in frames, a dotted eighth at the tempo now. */
        private double lengthFrames() {
            return Math.min(lineLeft.length - 2, DELAY_BEATS * 60.0 / bpm * sampleRate);
        }

        @Override
        boolean ringing() {
            return loudest > QUIET;
        }

        @Override
        boolean blends() {
            return false;
        }

        @Override
        void reset() {
            Arrays.fill(lineLeft, 0.0f);
            Arrays.fill(lineRight, 0.0f);
            dampedLeft = dampedRight = loudest = 0;
            delayFrames = wantedDelayFrames = lengthFrames();
        }

        @Override
        void apply(float[] mix, int left) {
            // the time glides rather than jumps, so a new tempo bends the repeats instead of clicking
            wantedDelayFrames = lengthFrames();
            delayFrames += (wantedDelayFrames - delayFrames) * glide;
            double feedback = 0.3 + 0.62 * position;
            double level = 0.3 + 0.25 * position;
            double readLeft = read(lineLeft);
            double readRight = read(lineRight);
            dampedLeft += (readLeft - dampedLeft) * damping;
            dampedRight += (readRight - dampedRight) * damping;

            // what goes in is what the finger lets in; the line itself runs either way
            // into a soft clip, which is what keeps a delay fed this hard from running away, and
            // what a tape echo pushed as far does of its own accord
            lineLeft[writeAt] = (float) Math.tanh(mix[left] * wet + dampedRight * feedback);
            lineRight[writeAt] = (float) dampedLeft;
            writeAt = (writeAt + 1) % lineLeft.length;

            double heard = Math.max(Math.abs(readLeft), Math.abs(readRight));
            loudest = Math.max(heard, loudest * 0.9999);
            mix[left] += (float) (readLeft * level);
            mix[left + 1] += (float) (readRight * level);
        }

        /** The line {@code delayFrames} back, between two frames, so a gliding time does not step. */
        private double read(float[] line) {
            double at = writeAt - delayFrames + line.length;
            int before = (int) at;
            double part = at - before;
            return line[before % line.length] * (1 - part) + line[(before + 1) % line.length] * part;
        }
    }
}
