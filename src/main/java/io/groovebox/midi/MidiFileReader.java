package io.groovebox.midi;

import io.groovebox.model.Note;
import io.groovebox.model.Voice;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

public final class MidiFileReader {

    private MidiFileReader() {
    }

    public static List<Note> readTrack(Sequence sequence, int trackIndex) {
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IllegalArgumentException("Only PPQ-divided MIDI files are supported");
        }
        Track[] tracks = sequence.getTracks();
        if (trackIndex < 0 || trackIndex >= tracks.length) {
            throw new IllegalArgumentException("No track at index " + trackIndex);
        }
        int resolution = sequence.getResolution();
        Track track = tracks[trackIndex];
        Map<Integer, Deque<PendingNote>> pending = new HashMap<>();
        List<Note> notes = new ArrayList<>();

        for (int i = 0; i < track.size(); i++) {
            MidiEvent event = track.get(i);
            MidiMessage message = event.getMessage();
            if (!(message instanceof ShortMessage shortMessage)) {
                continue;
            }
            int pitch = shortMessage.getData1();
            if (shortMessage.getCommand() == ShortMessage.NOTE_ON && shortMessage.getData2() > 0) {
                pending.computeIfAbsent(pitch, key -> new ArrayDeque<>())
                        .addLast(new PendingNote(event.getTick(), shortMessage.getData2()));
            } else if (isNoteOff(shortMessage)) {
                Deque<PendingNote> queue = pending.get(pitch);
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
                PendingNote start = queue.removeFirst();
                double beat = (double) start.tick() / resolution;
                double durationBeats = (double) (event.getTick() - start.tick()) / resolution;
                notes.add(new Note(beat, new Voice.Pitch(pitch), durationBeats, start.velocity() / 127f));
            }
        }

        notes.sort(Comparator.comparingDouble(Note::beat)
                .thenComparingInt(note -> ((Voice.Pitch) note.voice()).midiNote()));
        return List.copyOf(notes);
    }

    public static double readTempo(Sequence sequence) {
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof MetaMessage metaMessage && metaMessage.getType() == 0x51) {
                    byte[] data = metaMessage.getData();
                    int microsecondsPerQuarterNote =
                            ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                    return 60_000_000.0 / microsecondsPerQuarterNote;
                }
            }
        }
        return 120.0;
    }

    public static int readProgram(Sequence sequence, int trackIndex) {
        Track track = sequence.getTracks()[trackIndex];
        for (int i = 0; i < track.size(); i++) {
            MidiMessage message = track.get(i).getMessage();
            if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                return shortMessage.getData1();
            }
        }
        return 0;
    }

    public static String trackName(Sequence sequence, int trackIndex) {
        Track track = sequence.getTracks()[trackIndex];
        for (int i = 0; i < track.size(); i++) {
            MidiMessage message = track.get(i).getMessage();
            if (message instanceof MetaMessage metaMessage && metaMessage.getType() == 0x03) {
                return new String(metaMessage.getData());
            }
        }
        return "";
    }

    public static OptionalInt channelOf(Sequence sequence, int trackIndex) {
        Track track = sequence.getTracks()[trackIndex];
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage shortMessage) {
                return OptionalInt.of(shortMessage.getChannel());
            }
        }
        return OptionalInt.empty();
    }

    private static boolean isNoteOff(ShortMessage message) {
        return message.getCommand() == ShortMessage.NOTE_OFF
                || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0);
    }

    private record PendingNote(long tick, int velocity) {
    }
}
