package io.groovebox.audio;

public record Transport(double bpm, int sampleRate) {

    public Transport {
        if (!Double.isFinite(bpm) || bpm <= 0.0) {
            throw new IllegalArgumentException("BPM must be positive and finite");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }
    }

    public long frameAtBeat(double beat) {
        return Math.round(beat * 60.0 / bpm * sampleRate);
    }
}
