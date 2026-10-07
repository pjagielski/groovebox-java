package io.groovebox.model;

public sealed interface Voice permits Drum, Voice.Pitch {

    record Pitch(int midiNote) implements Voice {
        public Pitch {
            if (midiNote < 0 || midiNote > 127) {
                throw new IllegalArgumentException("MIDI note must be 0-127");
            }
        }
    }
}
