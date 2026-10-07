package io.groovebox.model;

import java.util.Objects;

public record Note(double beat, Voice voice, double durationBeats, float velocity, Envelope envelope) {

    public Note(double beat, Voice voice, double durationBeats, float velocity) {
        this(beat, voice, durationBeats, velocity, Envelope.NONE);
    }

    public Note {
        Objects.requireNonNull(voice, "voice");
        Objects.requireNonNull(envelope, "envelope");
        if (beat < 0.0) {
            throw new IllegalArgumentException("Beat cannot be negative");
        }
        if (durationBeats <= 0.0) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        if (!Float.isFinite(velocity) || velocity < 0.0f || velocity > 1.0f) {
            throw new IllegalArgumentException("Velocity must be between 0 and 1");
        }
    }
}
