package io.groovebox.audio;

import io.groovebox.model.Drum;

import java.util.Random;

final class DrumSamples {

    private DrumSamples() {
    }

    static Sample create(Drum drum, int sampleRate) {
        return switch (drum) {
            case KICK -> kick(sampleRate);
            case SNARE -> snare(sampleRate);
            case CLOSED_HAT -> hat(sampleRate, 0.055, 90, 43);
            case OPEN_HAT -> hat(sampleRate, 0.30, 13, 44);
            case CLAP -> clap(sampleRate);
        };
    }

    private static Sample kick(int sampleRate) {
        int frames = (int) (0.40 * sampleRate);
        float[] data = new float[frames];
        double phase = 0.0;
        for (int i = 0; i < frames; i++) {
            double time = (double) i / sampleRate;
            double frequency = 45.0 + 130.0 * Math.exp(-time * 38.0);
            phase += 2.0 * Math.PI * frequency / sampleRate;
            double envelope = Math.exp(-time * 7.5);
            data[i] = (float) (
                    Math.sin(phase) * envelope * 0.95
                            + Math.exp(-time * 900.0) * 0.25
            );
        }
        return Sample.mono(data);
    }

    private static Sample snare(int sampleRate) {
        int frames = (int) (0.22 * sampleRate);
        float[] data = new float[frames];
        Random random = new Random(42);
        double highPass = 0.0;
        double previous = 0.0;
        for (int i = 0; i < frames; i++) {
            double time = (double) i / sampleRate;
            double noise = random.nextDouble() * 2.0 - 1.0;
            highPass = 0.72 * (highPass + noise - previous);
            previous = noise;
            double tone = Math.sin(2.0 * Math.PI * 185.0 * time) * Math.exp(-time * 34.0);
            data[i] = (float) ((highPass * Math.exp(-time * 24.0) * 0.8 + tone * 0.45) * 0.9);
        }
        return Sample.mono(data);
    }

    private static Sample hat(int sampleRate, double length, double decay, long seed) {
        int frames = (int) (length * sampleRate);
        float[] data = new float[frames];
        Random random = new Random(seed);
        double highPass = 0.0;
        double previous = 0.0;
        for (int i = 0; i < frames; i++) {
            double time = (double) i / sampleRate;
            double noise = random.nextDouble() * 2.0 - 1.0;
            highPass = 0.90 * (highPass + noise - previous);
            previous = noise;
            data[i] = (float) (highPass * Math.exp(-time * decay) * 0.6);
        }
        return Sample.mono(data);
    }

    private static Sample clap(int sampleRate) {
        int frames = (int) (0.28 * sampleRate);
        float[] data = new float[frames];
        Random random = new Random(45);
        double highPass = 0.0;
        double previous = 0.0;
        int[] bursts = {0, (int) (0.010 * sampleRate), (int) (0.021 * sampleRate)};
        for (int i = 0; i < frames; i++) {
            double time = (double) i / sampleRate;
            double noise = random.nextDouble() * 2.0 - 1.0;
            highPass = 0.85 * (highPass + noise - previous);
            previous = noise;
            double envelope = Math.exp(-time * 11.0) * 0.35;
            for (int burst : bursts) {
                if (i >= burst) {
                    envelope += Math.exp(-((double) (i - burst) / sampleRate) * 160.0) * 0.6;
                }
            }
            data[i] = (float) (highPass * envelope * 0.55);
        }
        return Sample.mono(data);
    }
}
