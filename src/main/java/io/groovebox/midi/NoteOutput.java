package io.groovebox.midi;

interface NoteOutput extends AutoCloseable {

    void noteOn(int pitch, int velocity);

    void noteOff(int pitch);

    @Override
    default void close() {
    }
}
