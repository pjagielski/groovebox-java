package io.groovebox.audio;

import java.util.function.DoubleSupplier;

/**
 * A loop of recorded audio played in time with the jam: the sample is read with a fractional index
 * at whatever rate makes its bars last as long as the jam's, so a break cut at 120 BPM fills the
 * same bars at 128. The rate is asked for every frame, which is what lets a tempo change while it
 * plays re-time the rest of it rather than only the next pass.
 *
 * <p>Faster means higher, as a sampler has always done: what it would take to move the tempo and
 * leave the pitch where it is has a step of its own (12.4).
 *
 * <p>It is ended, rather than left to play out, both when the jam stops and when the next pass
 * starts — over a few milliseconds, since a loop cut dead halfway up a wave clicks.
 */
final class LoopVoice implements VoiceSource {

    private final Sample audio;
    private final DoubleSupplier rate;
    // how long the fade at the end lasts; a handful of milliseconds, no more
    private final int fadeFrames;
    private final double until;
    private double position;
    private boolean stopped;
    // frames of the fade still to play, or -1 while it is not fading
    private int fading = -1;

    /**
     * {@code rate} is frames of the sample read per frame played: 2 is twice as fast, an octave up.
     * {@code fadeFrames} is how long {@link #stop()} takes to fall silent.
     */
    LoopVoice(Sample audio, DoubleSupplier rate, int fadeFrames) {
        this(audio, rate, fadeFrames, 0);
    }

    /**
     * The same, starting {@code from} frames into the sample rather than at its beginning: what a
     * stutter needs, which takes the loop back to where it had got to rather than to its start.
     */
    LoopVoice(Sample audio, DoubleSupplier rate, int fadeFrames, double from) {
        this(audio, rate, fadeFrames, from, audio.frameCount());
    }

    /**
     * The same, stopping at {@code until} rather than at the end of the sample: what one slice of a
     * loop is, sounded on its own.
     */
    LoopVoice(Sample audio, DoubleSupplier rate, int fadeFrames, double from, double until) {
        this.audio = audio;
        this.rate = rate;
        this.fadeFrames = Math.max(1, fadeFrames);
        this.position = Math.max(0, from);
        this.until = Math.min(audio.frameCount(), Math.max(from + 1, until));
    }

    @Override
    public float next() {
        float[] both = new float[2];
        next(both);
        return (both[0] + both[1]) / 2;
    }

    @Override
    public void next(float[] stereoOut) {
        int frame = (int) position;
        if (stopped || frame >= until - 1) {
            stopped = true;
            stereoOut[0] = 0;
            stereoOut[1] = 0;
            return;
        }
        // between two frames of the sample: what is read at a rate that is rarely a whole number
        double blend = position - frame;
        float level = 1.0f;
        if (fading >= 0) {
            level = Math.max(0.0f, (float) --fading / fadeFrames);
            if (fading <= 0) {
                stopped = true;
            }
        }
        stereoOut[0] = (float) (audio.leftAt(frame) * (1 - blend) + audio.leftAt(frame + 1) * blend) * level;
        stereoOut[1] = (float) (audio.rightAt(frame) * (1 - blend) + audio.rightAt(frame + 1) * blend) * level;
        position += Math.max(0, rate.getAsDouble());
    }

    @Override
    public boolean finished() {
        return stopped || position >= until - 1;
    }

    /**
     * Ends it: what the jam stopping does, and what a loop being started again does to the pass
     * still playing. It falls to nothing over {@code fadeFrames} rather than at once, so the end of
     * a pass is not a click.
     */
    void stop() {
        if (fading < 0) {
            fading = fadeFrames;
        }
    }
}
