package io.groovebox.audio;

import java.util.Arrays;

/** Reused per-track buffers that measure the sum of simultaneous voices without touching the audio mix. */
final class LevelMeters {
    private float[][] tracks = new float[0][];
    private final int frames;
    LevelMeters(int frames) { this.frames = frames; }
    void begin(int count) {
        if (tracks.length != count) tracks = new float[count][frames * 2];
        for (float[] track : tracks) Arrays.fill(track, 0);
    }
    void add(int track, int frame, float left, float right) {
        if (track < 0 || track >= tracks.length) return;
        tracks[track][frame * 2] += left; tracks[track][frame * 2 + 1] += right;
    }
    MixLevels finish(float[] output) {
        float[] peaks = new float[tracks.length];
        for (int i = 0; i < peaks.length; i++) peaks[i] = peak(tracks[i]);
        return new MixLevels(peaks, peak(output));
    }
    private static float peak(float[] audio) {
        float peak = 0; for (float value : audio) peak = Math.max(peak, Math.abs(value)); return peak;
    }
}
