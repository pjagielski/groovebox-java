package io.groovebox.synth;

/**
 * Shared DSP primitives ported from the novasaw synth (Sandbox/novasaw,
 * Source/MainComponent.cpp), used by every {@code *Synth} patch class so the
 * per-sample math lives in exactly one place.
 */
public final class NovasawDsp {

    private NovasawDsp() {
    }

    static float envelopeCoefficient(float seconds, int sampleRate) {
        float timeConstant = Math.max(0.001f, seconds) * 0.35f;
        return (float) (1.0 - Math.exp(-1.0 / (timeConstant * sampleRate)));
    }

    static float polyBlep(float phase, float phaseIncrement) {
        phaseIncrement = clamp(phaseIncrement, 0.0f, 0.5f);
        if (phaseIncrement <= 0.0f) {
            return 0.0f;
        }
        if (phase < phaseIncrement) {
            float t = phase / phaseIncrement;
            return t + t - t * t - 1.0f;
        }
        if (phase > 1.0f - phaseIncrement) {
            float t = (phase - 1.0f) / phaseIncrement;
            return t * t + t + t + 1.0f;
        }
        return 0.0f;
    }

    static float polyBlepSaw(float phase, float phaseIncrement) {
        return phase * 2.0f - 1.0f - polyBlep(phase, phaseIncrement);
    }

    /**
     * A square as the difference of two saws half a cycle apart. A square has two steps in it where
     * a saw has one, and this gets both corrected for nothing extra: each saw brings its own
     * correction to its own step. High for the first half of the cycle, low for the second.
     */
    static float polyBlepSquare(float phase, float phaseIncrement) {
        float opposite = phase + 0.5f;
        if (opposite >= 1.0f) {
            opposite -= 1.0f;
        }
        return polyBlepSaw(opposite, phaseIncrement) - polyBlepSaw(phase, phaseIncrement);
    }

    /**
     * The diode shaper with its level taken out of it: what comes out is about as loud as what went
     * in, whatever the drive, so the knob adds dirt rather than volume. The shaper alone runs from
     * about 0.7 times the level at no drive to seven times it at full, which is most of what a drive
     * knob sounds like when nothing corrects for it.
     */
    public static float shapeDiodeLevelled(float input, float drive) {
        return shapeDiode(input, drive) / driveGain(drive);
    }

    /**
     * What the shaper does to the level at {@code drive}: measured through it with a saw of the
     * level a patch's voices reach, and fitted, since it is a curve rather than a line - the harder
     * it is driven, the more of what it adds it clips straight back off.
     */
    static float driveGain(float drive) {
        float positive = Math.max(0.0f, drive);
        return 0.74f + 5.3f * positive / (1.0f + 0.32f * positive);
    }

    public static float shapeDiode(float input, float drive) {
        float driven = input * (1.0f + drive * 7.0f);
        float positive = 1.0f - (float) Math.exp(-Math.max(0.0f, driven));
        float negative = -0.72f * (1.0f - (float) Math.exp(-Math.max(0.0f, -driven * 1.25f)));
        return (driven >= 0.0f ? positive : negative) * (0.9f + drive * 0.55f);
    }

    static float smoothStep(float value) {
        value = clamp(value, 0.0f, 1.0f);
        return value * value * (3.0f - 2.0f * value);
    }

    static float shapeEnergy(float value) {
        return (float) Math.pow(clamp(value, 0.0f, 1.0f), 1.18);
    }

    static float shapeTone(float value) {
        return (float) Math.pow(clamp(value, 0.0f, 1.0f), 1.08);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static double wrapTwoPi(double phase) {
        double twoPi = 2.0 * Math.PI;
        return phase >= twoPi ? phase - twoPi : phase;
    }

    static double wrapUnitPhase(double phase) {
        phase -= Math.floor(phase);
        return phase < 0.0 ? phase + 1.0 : phase;
    }

    /**
     * novasaw seeds each unison voice's starting phase and drift/vibrato LFO phase from this
     * hash instead of zero (Sandbox/novasaw, MainComponent.cpp:130, MainComponent.cpp:3191-3201).
     * Without it every unison voice's drift LFO starts locked together — since their rates only
     * differ by a small amount, they stay correlated for seconds before audibly spreading out,
     * which reads as the pitch "coming apart" specifically late into a held note's tail.
     */
    static double deterministicPhaseJitter(int seedA, int seedB, int seedC) {
        int seed = seedA * 747796405
                + seedB * (int) 2891336453L
                + seedC * (int) 277803737L;
        seed ^= seed >>> 16;
        seed *= (int) 2246822519L;
        seed ^= seed >>> 13;
        return (seed & 0x00ffffff) / (double) 0x01000000;
    }

    /** One ADSR per voice and one per filter, with coefficients updated when their time changes. */
    static final class Adsr {
        private enum Stage { ATTACK, DECAY, SUSTAIN, RELEASE, IDLE }

        private Stage stage = Stage.ATTACK;
        private float level;
        private float attackSeconds = Float.NaN;
        private float decaySeconds = Float.NaN;
        private float releaseSeconds = Float.NaN;
        private float attackCoefficient;
        private float attackTarget;
        private float decayCoefficient;
        private float releaseCoefficient;
        private int attackFrames;

        float next(int frame, int heldFrames, float attackSeconds, float decaySeconds, float sustainLevel,
                   float releaseSeconds, int sampleRate) {
            if (frame >= heldFrames && stage != Stage.IDLE) {
                stage = Stage.RELEASE;
            }
            switch (stage) {
                case ATTACK -> {
                    if (Float.compare(this.attackSeconds, attackSeconds) != 0) {
                        this.attackSeconds = attackSeconds;
                        attackFrames = Math.max(1, (int) Math.ceil(attackSeconds * sampleRate));
                        attackCoefficient = envelopeCoefficient(attackSeconds, sampleRate);
                        int remaining = Math.max(1, attackFrames - frame);
                        double leftover = Math.pow(1.0 - attackCoefficient, remaining);
                        attackTarget = (float) ((1.0 - level * leftover) / (1.0 - leftover));
                    }
                    if (frame + 1 >= attackFrames) {
                        level = 1.0f;
                        stage = Stage.DECAY;
                    } else {
                        level = Math.min(1.0f, level + (attackTarget - level) * attackCoefficient);
                    }
                }
                case DECAY, SUSTAIN -> {
                    if (Float.compare(this.decaySeconds, decaySeconds) != 0) {
                        this.decaySeconds = decaySeconds;
                        decayCoefficient = envelopeCoefficient(decaySeconds, sampleRate);
                    }
                    if (Math.abs(level - sustainLevel) <= 1e-5f) {
                        level = sustainLevel;
                        stage = Stage.SUSTAIN;
                    } else {
                        level += (sustainLevel - level) * decayCoefficient;
                        stage = Stage.DECAY;
                    }
                }
                case RELEASE -> {
                    if (Float.compare(this.releaseSeconds, releaseSeconds) != 0) {
                        this.releaseSeconds = releaseSeconds;
                        releaseCoefficient = envelopeCoefficient(releaseSeconds, sampleRate);
                    }
                    level -= level * releaseCoefficient;
                    if (level <= 0.0005f) {
                        level = 0.0f;
                        stage = Stage.IDLE;
                    }
                }
                case IDLE -> { }
            }
            return level;
        }

        boolean finished() {
            return stage == Stage.IDLE;
        }

        /**
         * Struck again from the top. A voice makes a new one of these per note and never needs
         * this; a channel's filter envelope has one for the whole session and is struck on every
         * hit that plays into it, so it needs somewhere to go back to.
         */
        void reset() {
            stage = Stage.ATTACK;
            level = 0;
            // the coefficients follow from the times, and the attack's target follows from the
            // level as well, so it has to be worked out again from this one
            attackSeconds = Float.NaN;
        }
    }

    static final class LowpassFilter {
        private float low;
        private float band;

        void reset() {
            low = 0;
            band = 0;
        }

        float process(float input, float cutoffHz, float resonance, int sampleRate) {
            float limitedCutoff = clamp(cutoffHz, 40.0f, sampleRate * 0.45f);
            float g = (float) Math.tan(Math.PI * limitedCutoff / sampleRate);
            float k = clamp(1.82f - resonance * 1.25f, 0.45f, 2.0f);
            float safeInput = clamp(input, -3.0f, 3.0f);
            float denominator = 1.0f / (1.0f + g * (g + k));
            float high = (safeInput - low - (k + g) * band) * denominator;
            float newBand = band + g * high;
            float newLow = low + g * newBand;
            band = clamp(newBand + g * high, -3.0f, 3.0f);
            low = clamp(newLow + g * newBand, -3.0f, 3.0f);
            return newLow;
        }
    }
}
