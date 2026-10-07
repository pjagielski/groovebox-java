package io.groovebox.audio;

import io.groovebox.model.Envelope;

import java.util.Arrays;

/**
 * A piece of audio the engine can play: a drum hit, a note rendered in advance, a loop off a
 * record. One channel or two — a drum sample is the same in both ears, a break is not, and the
 * engine is stereo from the mix on, so it is kept the way it was read.
 */
public final class Sample {
    private final float[] left;
    private final float[] right;

    private Sample(float[] left, float[] right) {
        if (left.length == 0) {
            throw new IllegalArgumentException("Sample cannot be empty");
        }
        if (right.length != left.length) {
            throw new IllegalArgumentException("A sample's channels are the same length");
        }
        this.left = left;
        this.right = right;
    }

    public static Sample mono(float... data) {
        float[] copy = Arrays.copyOf(data, data.length);
        return new Sample(copy, copy);
    }

    /** Two channels, as a stereo file was read: the same length, and kept apart. */
    public static Sample stereo(float[] left, float[] right) {
        return new Sample(Arrays.copyOf(left, left.length), Arrays.copyOf(right, right.length));
    }

    public int frameCount() {
        return left.length;
    }

    /** Whether the two channels differ; a mono sample is the same array twice. */
    public boolean stereo() {
        return left != right;
    }

    float valueAt(int frame) {
        return left[frame];
    }

    float leftAt(int frame) {
        return left[frame];
    }

    float rightAt(int frame) {
        return right[frame];
    }

    /** Defensive copy of both channels, left then right; a mono sample gives the same twice. */
    public float[][] copyStereo() {
        return new float[][] {Arrays.copyOf(left, left.length), Arrays.copyOf(right, right.length)};
    }

    /** Defensive copy of what is heard in the middle, for callers that render their own mix. */
    public float[] copyMono() {
        if (!stereo()) {
            return Arrays.copyOf(left, left.length);
        }
        float[] both = new float[left.length];
        for (int frame = 0; frame < both.length; frame++) {
            both[frame] = (left[frame] + right[frame]) / 2;
        }
        return both;
    }

    /**
     * A copy shaped by {@code envelope}: decaying to a thousandth of its level (-60 dB) over the
     * decay time and cut there, then fading out linearly over the release time once
     * {@code heldFrames} - the length of its note - have passed. Tuned first, so the times are
     * the times the tuned sample is heard for.
     */
    Sample shaped(Envelope envelope, int heldFrames, int sampleRate) {
        if (!envelope.shapes()) {
            return this;
        }
        if (envelope.semitones() != 0.0) {
            return tuned(envelope.semitones()).shaped(envelope.tunedTo(0.0), heldFrames, sampleRate);
        }
        int length = left.length;
        double decayPerFrame = 0.0;
        if (envelope.decaySeconds() > 0.0) {
            decayPerFrame = Math.log(1000.0) / envelope.decaySeconds() / sampleRate;
            length = Math.min(length, (int) Math.round(envelope.decaySeconds() * sampleRate));
        }
        int releaseFrames = (int) Math.round(envelope.releaseSeconds() * sampleRate);
        if (releaseFrames > 0) {
            length = Math.min(length, heldFrames + releaseFrames);
        }
        float[] shapedLeft = new float[Math.max(1, length)];
        float[] shapedRight = stereo() ? new float[shapedLeft.length] : shapedLeft;
        for (int frame = 0; frame < shapedLeft.length; frame++) {
            double gain = Math.exp(-decayPerFrame * frame);
            if (releaseFrames > 0 && frame >= heldFrames) {
                gain *= 1.0 - (double) (frame - heldFrames) / releaseFrames;
            }
            shapedLeft[frame] = (float) (left[frame] * gain);
            if (stereo()) {
                shapedRight[frame] = (float) (right[frame] * gain);
            }
        }
        return new Sample(shapedLeft, shapedRight);
    }

    /**
     * A copy {@code semitones} higher or lower, the way a sampler tunes a drum: read faster or
     * slower, so higher is shorter, with the line between two frames for what falls between them.
     */
    private Sample tuned(double semitones) {
        double rate = Math.pow(2.0, semitones / 12.0);
        int length = Math.max(1, (int) Math.ceil(left.length / rate));
        float[] tunedLeft = new float[length];
        float[] tunedRight = stereo() ? new float[length] : tunedLeft;
        int last = left.length - 1;
        for (int frame = 0; frame < length; frame++) {
            double at = Math.min(last, frame * rate);
            int before = (int) at;
            int after = Math.min(last, before + 1);
            float between = (float) (at - before);
            tunedLeft[frame] = left[before] + (left[after] - left[before]) * between;
            if (stereo()) {
                tunedRight[frame] = right[before] + (right[after] - right[before]) * between;
            }
        }
        return new Sample(tunedLeft, tunedRight);
    }
}
