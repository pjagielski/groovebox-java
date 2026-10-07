package io.groovebox.model;

import java.util.List;

public record Song(double bpm, int beatsPerBar, List<Track> tracks) {

    public Song {
        if (!Double.isFinite(bpm) || bpm <= 0.0) {
            throw new IllegalArgumentException("BPM must be positive and finite");
        }
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException("Beats per bar must be positive");
        }
        tracks = List.copyOf(tracks);
    }
}
