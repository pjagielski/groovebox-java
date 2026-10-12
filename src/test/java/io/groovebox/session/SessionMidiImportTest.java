package io.groovebox.session;

import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionMidiImportTest {
    @TempDir Path dir;

    private static Session session() {
        Session session = new Session();
        session.startWith(TrackList.startingWith("Lead", new MelodySource.OwnNotes(List.of(), null),
                Instrument.of("pluck")), Sounds.resolveSynth("pluck"));
        session.setBpm(96);
        session.setBars(2);
        return session;
    }

    /** Metadata first, then a named line per pitch: the track numbers are those a DAW writes. */
    private Path midi(String fileName, int... pitches) throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        sequence.createTrack();
        for (int pitch : pitches) {
            var track = sequence.createTrack();
            byte[] name = "Lead".getBytes(StandardCharsets.UTF_8);
            track.add(new MidiEvent(new MetaMessage(0x03, name, name.length), 0));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, 100), 0));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0), 480));
        }
        Path file = dir.resolve(fileName);
        MidiSystem.write(sequence, 1, file.toFile());
        return file;
    }

    @Test
    void addsOnlyTheChosenPartAndKeepsItsRangeAndRepeatSetting() throws Exception {
        Session session = session();
        Path file = midi("bass-and-horns.mid", 48, 67);
        var window = new MidiWindow(file, 2, 0, 1, true);

        assertEquals(1, session.addMidiWindows(List.of(window)));

        assertEquals(3, session.tracks().size());
        assertEquals(window, ((StudioTrack.Melody) session.tracks().selected()).window());
        var heard = (MelodyTrack) session.jam().song().tracks().get(2);
        assertEquals(List.of(67, 67), heard.notes().stream()
                .map(note -> ((Voice.Pitch) note.voice()).midiNote()).toList());
        assertEquals(96, session.bpm());
        assertEquals(2, session.bars());
        assertTrue(session.undo());
        assertEquals(2, session.tracks().size());
        assertFalse(session.undo());
        assertTrue(session.redo());
        assertEquals(window, ((StudioTrack.Melody) session.tracks().selected()).window());
    }

    @Test
    void anInvalidSelectedPartDoesNotAddEarlierSelections() throws Exception {
        Session session = session();
        Path file = midi("two.mid", 48, 67);
        var jam = session.jam();

        assertThrows(IllegalArgumentException.class, () -> session.addMidiWindows(List.of(
                new MidiWindow(file, 2, 0), new MidiWindow(file, 0, 0))));

        assertEquals(2, session.tracks().size());
        assertSame(jam, session.jam());
        assertFalse(session.undo());
    }

    @Test
    void importsEveryNoteTrackOfSeveralFilesWithIndependentInstruments() throws Exception {
        Session session = session();
        Path first = midi("first.mid", 60, 48);
        Path second = midi("second.mid", 67);

        assertEquals(3, session.addMidiFiles(List.of(first, second)));

        assertEquals(List.of("Drums", "Lead", "Lead 2", "Lead 3", "Lead 4"),
                session.tracks().tracks().stream().map(StudioTrack::name).toList());
        assertEquals(4, session.tracks().selectedIndex());
        assertEquals(96, session.bpm());
        assertEquals(2, session.bars());
        for (int i = 2; i < 5; i++) {
            var track = (StudioTrack.Melody) session.tracks().get(i);
            assertEquals(i - 1, track.instrument().channel());
            assertNotSame(((StudioTrack.Melody) session.tracks().get(i - 1)).instrument().synth(),
                    track.instrument().synth());
            var heard = (MelodyTrack) session.jam().song().tracks().get(i);
            assertEquals(List.of(60, 48, 67).get(i - 2).intValue(),
                    ((Voice.Pitch) heard.notes().getFirst().voice()).midiNote());
            assertEquals(8, heard.patternLengthBeats());
        }
        assertEquals(new MidiWindow(first.toAbsolutePath().normalize(), 2, 0),
                ((StudioTrack.Melody) session.tracks().get(3)).window());
        session.setMuted(2, true);
        assertEquals(0, session.jam().song().tracks().get(2).gain());
        assertEquals(1, session.jam().song().tracks().get(3).gain());
    }

    @Test
    void theWholeImportIsOneUndoAndRedoKeepsTheInstruments() throws Exception {
        Session session = session();
        var before = session.tracks().tracks();
        session.addMidiFiles(List.of(midi("first.mid", 60, 48), midi("second.mid", 67)));
        var imported = session.tracks().tracks();

        assertTrue(session.undo());
        assertEquals(before, session.tracks().tracks());
        assertEquals(0, session.tracks().selectedIndex());
        assertFalse(session.undo());
        assertTrue(session.redo());
        assertEquals(imported, session.tracks().tracks());
        assertEquals(4, session.tracks().selectedIndex());
    }

    @Test
    void aBadFileLeavesTheTracksPublishedJamAndHistoryAlone() throws Exception {
        Session session = session();
        var before = session.tracks().tracks();
        var jam = session.jam();
        Path broken = dir.resolve("broken.mid");
        Files.writeString(broken, "not MIDI");

        assertThrows(Exception.class, () -> session.addMidiFiles(List.of(midi("good.mid", 60), broken)));
        assertEquals(before, session.tracks().tracks());
        assertSame(jam, session.jam());
        assertFalse(session.undo());
    }

    @Test
    void aFileWithoutNotesExplainsWhyAndAddsNothing() throws Exception {
        Session session = session();

        var failure = assertThrows(IllegalArgumentException.class,
                () -> session.addMidiFiles(List.of(midi("empty.mid"))));

        assertTrue(failure.getMessage().contains("empty.mid"));
        assertEquals(2, session.tracks().size());
        assertFalse(session.undo());
    }

    @Test
    void cancellingTheChooserIsNotAnEdit() throws Exception {
        Session session = session();
        var jam = session.jam();

        assertEquals(0, session.addMidiFiles(List.of()));

        assertSame(jam, session.jam());
        assertFalse(session.undo());
    }

    @Test
    void choosingAnotherSourceAndRangeOnlyChangesThatTrackAndCanBeUndone() throws Exception {
        Session session = session();
        Path file = midi("two.mid", 60, 48);
        session.addMidiFiles(List.of(file));
        var first = (StudioTrack.Melody) session.tracks().get(2);
        var other = session.tracks().get(3);
        var next = new MidiWindow(file, 2, 0, 1, true);

        session.changeMidiWindow(2, next);

        var changed = (StudioTrack.Melody) session.tracks().get(2);
        assertEquals(next, changed.source());
        assertSame(first.instrument(), changed.instrument());
        assertSame(other, session.tracks().get(3));
        assertEquals(List.of(0.0, 4.0), ((MelodyTrack) session.jam().song().tracks().get(2)).notes()
                .stream().map(note -> note.beat()).toList());
        assertTrue(session.undo());
        assertEquals(first, session.tracks().get(2));
        assertTrue(session.redo());
        assertEquals(changed, session.tracks().get(2));
    }

    @Test
    void aBadSourceDoesNotChangeTheTrackOrAddAnUndoStep() throws Exception {
        Session session = session();
        Path file = midi("one.mid", 60);
        session.addMidiFiles(List.of(file));
        var before = session.tracks().get(2);
        var jam = session.jam();

        assertThrows(IllegalArgumentException.class,
                () -> session.changeMidiWindow(2, new MidiWindow(file, 99, 0, 1, true)));
        assertSame(before, session.tracks().get(2));
        assertSame(jam, session.jam());
        assertTrue(session.undo());
        assertEquals(2, session.tracks().size());
    }

    @Test
    void thePartChooserSkipsMetadataAndShowsWhereTheNotesBegin() throws Exception {
        Session session = session();
        Path file = midi("parts.mid", 60, 48);

        assertEquals(List.of(new MidiPart(1, "Lead", 1, 0, 1), new MidiPart(2, "Lead", 1, 0, 1)),
                session.midiParts(file));
    }

    @Test
    void aRangePastTheFileEndIsRejectedBeforeMakingAnEdit() throws Exception {
        Session session = session();
        Path file = midi("short.mid", 60);
        session.addMidiFiles(List.of(file));
        var before = session.tracks().get(2);

        assertThrows(IllegalArgumentException.class,
                () -> session.changeMidiWindow(2, new MidiWindow(file, 1, 0, 2, true)));
        assertSame(before, session.tracks().get(2));
        assertTrue(session.undo());
        assertEquals(2, session.tracks().size());
    }
}
