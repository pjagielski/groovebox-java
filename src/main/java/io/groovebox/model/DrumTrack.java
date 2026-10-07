package io.groovebox.model;

import java.util.Objects;

/**
 * A hand-authored, one-bar drum pattern: {@code steps} is a compact string (see
 * {@link PatternCompiler} for the accent characters) subdividing one bar of the song. When the
 * song's total length exceeds one bar (e.g. because a {@link MelodyTrack} is longer), the pattern
 * repeats to fill it.
 */
public record DrumTrack(Drum drum, String steps, float gain) implements Track {

    public DrumTrack {
        Objects.requireNonNull(drum, "drum");
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Pattern cannot be empty");
        }
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }
}
