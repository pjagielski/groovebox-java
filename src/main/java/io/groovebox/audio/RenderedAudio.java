package io.groovebox.audio;

import java.util.Arrays;

public final class RenderedAudio {
    private static final int CHANNELS = 2;

    private final int sampleRate;
    private final float[] interleavedStereo;

    RenderedAudio(int sampleRate, float[] interleavedStereo) {
        this.sampleRate = sampleRate;
        this.interleavedStereo = interleavedStereo;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int frameCount() {
        return interleavedStereo.length / CHANNELS;
    }

    public float sampleAt(int frame, int channel) {
        if (channel < 0 || channel >= CHANNELS) {
            throw new IllegalArgumentException("Channel must be 0 or 1");
        }
        return interleavedStereo[frame * CHANNELS + channel];
    }

    public float[] copySamples() {
        return Arrays.copyOf(interleavedStereo, interleavedStereo.length);
    }
}
