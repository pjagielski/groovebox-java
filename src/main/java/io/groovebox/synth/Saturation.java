package io.groovebox.synth;

/**
 * The shapes a signal can be driven into. Drive has always said how hard; this says what kind.
 *
 * <p>{@link #DIODE} is the one every patch here was levelled against and stays the default: an
 * asymmetric exponential diode, which is what a hand reaches for when it wants dirt. The other four
 * are each a different species rather than a different setting - a soft clip, a hard one, a pair of
 * diodes facing each other, and a fold, which stops being clipping altogether and starts adding
 * partials that were never in the sound.
 *
 * <p>Every shape is levelled, so turning the drive up or changing the shape changes the sound and
 * not the loudness. The levels are measured rather than derived - see {@link #gain} - because
 * these curves clip back off much of what they add, by an amount no formula here would guess.
 *
 * <p>What separates them once they are levelled is how much they lift what is quiet. A shape that
 * only deals with the peaks leaves the rest where it was; one that bends the whole curve raises
 * everything to keep its level, which is what makes saturation sound like compression. Measured,
 * as the gain a signal far below the level they were levelled at comes out with, at drive 1 and
 * at drive 2:
 *
 * <pre>
 *   Fold      1.35  2.88     only the peaks are turned round; the rest is least touched
 *   Hard      1.78  3.11     flattened rather than folded
 *   Soft      1.95  3.24     the gentlest of the bending shapes
 *   Diode     2.43  4.13     ours, and the default
 *   Tube      2.55  3.11     the one that lifts least once it is driven hard
 * </pre>
 *
 * <p>There were nine of these and there are five. The four that went - a rational S, a cubic, the
 * leaning diode and a rounded fold - each sat within a decibel or two of one that stayed, and a
 * knob with nine stops on it that sound like five is worse than a knob with five.
 *
 * <p>They are furthest apart in the middle of the drive knob and converge towards its top, because
 * every one of them is on its way to a square wave by then. The shape is a choice about the middle
 * of the knob; the top of it is where they all agree.
 */
public enum Saturation {

    /** The exponential diode the patches are levelled against: asymmetric, and the default. */
    DIODE("Diode"),
    /** A plain soft clip, tanh: the mildest of them, and the one that never quite gets there. */
    SOFT("Soft"),
    /** Clipped flat. Everything above the line is the line, and what is under it is left alone. */
    HARD("Hard"),
    /** Two soft diodes facing each other across a small bias, as a clipper's pair of them do. */
    TUBE("Tube"),
    /** Folded rather than clipped: past the top it comes back down, and keeps coming. */
    FOLD("Fold");

    /**
     * What the drive knob's 0..2 becomes for the shapes that take a gain. Seven, so that a shape is
     * driven as hard as {@link #DIODE} is at the same setting - that one multiplies its input by
     * {@code 1 + 7*drive}, and at half of it the others were plainly milder than the default, which
     * made changing shape feel like nothing had happened.
     */
    private static final float K = 7.0f;

    private final String label;

    Saturation(String label) {
        this.label = label;
    }

    /** The name the panel shows. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** {@code input} driven into this shape at {@code drive}, before its level is taken back off. */
    public float shape(float input, float drive) {
        if (this == DIODE) {
            return NovasawDsp.shapeDiode(input, drive);
        }
        float k = Math.max(0.0f, drive) * K;
        return switch (this) {
            case SOFT -> soft(input, k);
            case HARD -> Math.max(-1.0f, Math.min(1.0f, (1 + k) * input));
            case TUBE -> diode(input, k, false);
            case FOLD -> fold(input, k);
            case DIODE -> throw new IllegalStateException("handled above");
        };
    }

    /** The same, with the level it adds taken back off, so only the shape is heard. */
    public float levelled(float input, float drive) {
        return shape(input, drive) / gain(drive);
    }

    private static float soft(float input, float k) {
        return (float) Math.tanh(input * (1 + k));
    }

    /** Maps [0, inf) onto [0, 1): how far along a shape's range a drive is, whatever the drive. */
    private static double squash(double value) {
        return value / (1 + value);
    }

    private static float cubic(float input, float k) {
        double third = squash(Math.log1p(k)) / 3;
        return soft((float) ((input - third * input * input * input) / (1 - third)), k);
    }

    /**
     * Two soft diodes across a bias, divided by the slope at zero so the pair is the identity for
     * small signals before the soft clip that follows it - which then gives them its own gain, so
     * the shape as a whole is not transparent, only evenly behaved around zero. With
     * {@code leaning}, one leg is held at the bias instead of following the signal, which is what
     * makes it asymmetric and brings up the even harmonics.
     */
    private static float diode(float input, float k, boolean leaning) {
        double gain = 1 + 2 * k;
        double bias = 0.07 * squash(Math.log1p(k));
        double positive = Math.tanh(gain * (input + bias));
        double negative = Math.tanh(gain * (leaning ? bias : bias - input));
        double sech = 1 / Math.cosh(gain * bias);
        // the slope at zero: one leg's worth when it leans, both when it does not
        double slope = Math.max(1e-8, (leaning ? 1 : 2) * gain * sech * sech);
        return soft((float) ((positive - negative) / slope), k);
    }

    /** Past the top it turns round and comes back: a triangle wrapped over the driven signal. */
    private static float fold(float input, float k) {
        double driven = (1 + 0.5 * k) * input;
        double window = (driven + 1) % 4;
        if (window < 0) {
            window += 4;
        }
        return (float) (1 - Math.abs(window - 2));
    }

    /**
     * What this shape does to the level at {@code drive}, so it can be taken back off. Measured
     * rather than worked out: a saw at the level a patch's voices reach is driven through the shape
     * at every fifth of the knob, and read between those points. {@link #DIODE} keeps the fitted
     * curve it always had, so no patch made before any of this sounds different for it.
     *
     * <p>The saw is at 0.335, which is the level at which measuring DIODE reproduces the fit it was
     * given years before these other shapes existed - so they are all levelled against the same
     * thing rather than each against itself.
     */
    float gain(float drive) {
        if (this == DIODE) {
            return NovasawDsp.driveGain(drive);
        }
        float[] measured = LEVELS[ordinal()];
        float along = Math.max(0.0f, Math.min(1.0f, drive / 2.0f)) * (measured.length - 1);
        int below = (int) along;
        int above = Math.min(measured.length - 1, below + 1);
        float between = along - below;
        return measured[below] * (1 - between) + measured[above] * between;
    }

    /** Measured by SaturationLevels in the tests, at drive 0, 0.2, 0.4 ... 2. */
    private static final float[][] LEVELS = {
            {}, // DIODE keeps its own fit
            {0.9783f, 2.1400f, 2.9643f, 3.5075f, 3.8642f, 4.1055f, 4.2755f, 4.4000f, 4.4946f, 4.5687f, 4.6282f},
            {1.0000f, 2.4000f, 3.5686f, 4.0627f, 4.3217f, 4.4821f, 4.5913f, 4.6706f, 4.7307f, 4.7779f, 4.8160f},
            {0.9583f, 1.7694f, 2.1482f, 2.4386f, 2.7592f, 3.1407f, 3.5730f, 4.0127f, 4.3949f, 4.6660f, 4.8197f},
            {1.0000f, 1.7000f, 2.4000f, 3.0877f, 3.3636f, 3.3348f, 3.1856f, 3.0037f, 2.8466f, 2.7592f, 2.7745f},
    };
}
