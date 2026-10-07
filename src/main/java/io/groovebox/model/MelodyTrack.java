package io.groovebox.model;

import io.groovebox.audio.EffectSource;

import java.util.List;
import java.util.Objects;

/**
 * A pre-loaded phrase of notes — typically a windowed excerpt read from a MIDI file (see
 * {@code MidiFileReader} in the {@code midi} package). Unlike {@link DrumTrack}, a
 * {@code MelodyTrack} already spans its full {@code patternLengthBeats} and is never tiled; it's
 * the other tracks (and the song's total length) that adapt to it.
 *
 * <p>A track of drum notes can be given a {@code channel} of effects to be heard through, as a
 * loop can: a row of a drum grid with its own drive and echo. Null mixes its drums in dry. A
 * pitched note goes through its synth's own channel whatever this says.
 */
public record MelodyTrack(List<Note> notes, double patternLengthBeats, float gain, EffectSource channel)
        implements Track {

    public MelodyTrack {
        Objects.requireNonNull(notes, "notes");
        notes = List.copyOf(notes);
        if (patternLengthBeats <= 0.0) {
            throw new IllegalArgumentException("Pattern length must be positive");
        }
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }

    public MelodyTrack(List<Note> notes, double patternLengthBeats, float gain) {
        this(notes, patternLengthBeats, gain, null);
    }
}
