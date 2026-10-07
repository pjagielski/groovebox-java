package io.groovebox.midi;

import io.groovebox.model.Note;
import io.groovebox.model.Voice;

import org.junit.jupiter.api.Test;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MidiFileReaderTest {

    private static final int PPQ = 480;

    @Test
    void convertsTicksToBeatsAndPairsNoteOnWithNoteOff() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        addNoteOn(track, 0, 60, 100);
        addNoteOff(track, PPQ / 2, 60);
        addNoteOn(track, PPQ, 64, 90);
        addNoteOnWithZeroVelocity(track, PPQ + PPQ / 4, 64);

        List<Note> notes = MidiFileReader.readTrack(sequence, 0);

        assertEquals(List.of(
                new Note(0.0, new Voice.Pitch(60), 0.5, 100 / 127f),
                new Note(1.0, new Voice.Pitch(64), 0.25, 90 / 127f)
        ), notes);
    }

    @Test
    void pairsOverlappingNotesOfTheSamePitchInOrder() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        addNoteOn(track, 0, 60, 100);
        addNoteOn(track, PPQ / 4, 60, 80);
        addNoteOff(track, PPQ / 2, 60);
        addNoteOff(track, PPQ, 60);

        List<Note> notes = MidiFileReader.readTrack(sequence, 0);

        assertEquals(List.of(
                new Note(0.0, new Voice.Pitch(60), 0.5, 100 / 127f),
                new Note(0.25, new Voice.Pitch(60), 0.75, 80 / 127f)
        ), notes);
    }

    @Test
    void readsTempoFromMetaMessage() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        addTempo(track, 0, 96.0);

        assertEquals(96.0, MidiFileReader.readTempo(sequence), 0.01);
    }

    @Test
    void defaultsTempoTo120WhenNoTempoEventPresent() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        sequence.createTrack();

        assertEquals(120.0, MidiFileReader.readTempo(sequence), 0.01);
    }

    @Test
    void readsProgramFromProgramChangeMessage() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 1, 80, 0), 0));

        assertEquals(80, MidiFileReader.readProgram(sequence, 0));
    }

    private static void addNoteOn(Track track, long tick, int pitch, int velocity)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, velocity), tick));
    }

    private static void addNoteOff(Track track, long tick, int pitch) throws InvalidMidiDataException {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0), tick));
    }

    private static void addNoteOnWithZeroVelocity(Track track, long tick, int pitch)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, 0), tick));
    }

    private static void addTempo(Track track, long tick, double bpm) throws InvalidMidiDataException {
        int microsecondsPerQuarterNote = (int) Math.round(60_000_000.0 / bpm);
        byte[] data = {
                (byte) ((microsecondsPerQuarterNote >> 16) & 0xff),
                (byte) ((microsecondsPerQuarterNote >> 8) & 0xff),
                (byte) (microsecondsPerQuarterNote & 0xff)
        };
        track.add(new MidiEvent(new MetaMessage(0x51, data, data.length), tick));
    }
}
