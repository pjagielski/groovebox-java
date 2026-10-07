package io.groovebox.audio;

/**
 * Where {@link AudioEngine#playLive} sends melody notes that should sound on something other than
 * its built-in synths - typically an external MIDI device, a channel for each track sent to it.
 * Channels count from 0, as MIDI messages do.
 */
public interface NoteListener {

    void noteOn(int channel, int midiNote, int velocity);

    void noteOff(int channel, int midiNote);
}
