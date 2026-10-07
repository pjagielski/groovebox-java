package io.groovebox.session;

import io.groovebox.model.Note;
import io.groovebox.model.Voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteEditsTest {

    private static final double LOOP = 16;

    @Test
    void aClickPutsANoteWhereTheSixteenthItLandsInBegins() {
        List<Note> notes = NoteEdits.add(List.of(), 2.4, 60, 1.0, 0.8f, LOOP);

        assertEquals(1, notes.size());
        assertEquals(2.25, notes.getFirst().beat());
        assertEquals(1.0, notes.getFirst().durationBeats());
        assertEquals(60, midiNote(notes.getFirst()));
    }

    @Test
    void aNoteAddedNearTheEndIsCutShortByIt() {
        List<Note> notes = NoteEdits.add(List.of(), 15.5, 60, 4.0, 0.8f, LOOP);

        assertEquals(0.5, notes.getFirst().durationBeats());
        assertEquals(List.of(), NoteEdits.add(List.of(), 16.0, 60, 1.0, 0.8f, LOOP), "past the end: nothing added");
    }

    @Test
    void notesComeBackInTheOrderTheEngineExpects() {
        List<Note> notes = NoteEdits.add(NoteEdits.add(List.of(), 8.0, 60, 1.0, 0.8f, LOOP),
                2.0, 64, 1.0, 0.8f, LOOP);

        assertEquals(List.of(2.0, 8.0), notes.stream().map(Note::beat).toList());
    }

    @Test
    void aDraggedNoteMovesInTimeAndInPitchAndKeepsItsLength() {
        Note note = new Note(4.0, new Voice.Pitch(60), 2.0, 0.6f);

        List<Note> moved = NoteEdits.move(List.of(note), List.of(note), 1.3, -2, LOOP).notes();

        assertEquals(5.25, moved.getFirst().beat(), "snapped to the nearest sixteenth");
        assertEquals(58, midiNote(moved.getFirst()));
        assertEquals(2.0, moved.getFirst().durationBeats());
        assertEquals(0.6f, moved.getFirst().velocity());
    }

    @Test
    void aNoteCannotBeDraggedOffTheLoopOrOffTheKeyboard() {
        Note note = new Note(4.0, new Voice.Pitch(2), 1.0, 0.8f);

        List<Note> back = NoteEdits.move(List.of(note), List.of(note), -99, -9, LOOP).notes();
        assertEquals(0.0, back.getFirst().beat());
        assertEquals(0, midiNote(back.getFirst()));

        List<Note> on = NoteEdits.move(List.of(note), List.of(note), 99, 0, LOOP).notes();
        assertTrue(on.getFirst().beat() < LOOP, "still inside the loop: " + on.getFirst().beat());
        assertTrue(on.getFirst().beat() + on.getFirst().durationBeats() <= LOOP, "and so is its end");
    }

    @Test
    void draggingTheRightEdgeSetsWhereTheNoteEnds() {
        Note note = new Note(4.0, new Voice.Pitch(60), 1.0, 0.8f);

        assertEquals(2.5, NoteEdits.resize(List.of(note), note, 6.44, LOOP).getFirst().durationBeats());
        assertEquals(NoteEdits.SHORTEST, NoteEdits.resize(List.of(note), note, 1.0, LOOP).getFirst().durationBeats(),
                "never shorter than a sixteenth");
        assertEquals(12.0, NoteEdits.resize(List.of(note), note, 99, LOOP).getFirst().durationBeats(),
                "and never past the end of the loop");
    }

    @Test
    void severalNotesMoveAsOneAndKeepTheirShape() {
        Note low = new Note(2.0, new Voice.Pitch(60), 1.0, 0.8f);
        Note high = new Note(3.0, new Voice.Pitch(67), 0.5, 0.4f);

        NoteEdits.Edit edit = NoteEdits.move(List.of(low, high), List.of(low, high), 2.0, 5, LOOP);

        assertEquals(List.of(4.0, 5.0), edit.notes().stream().map(Note::beat).toList());
        assertEquals(List.of(65, 72), edit.notes().stream().map(NoteEditsTest::midiNote).toList());
        assertEquals(2, edit.touched().size(), "the notes the hand is still holding");
        assertEquals(0.5, edit.touched().get(1).durationBeats(), "each keeping its own length");
    }

    @Test
    void whatTheEdgeHoldsBackHoldsTheWholeHandfulBack() {
        Note early = new Note(0.0, new Voice.Pitch(60), 1.0, 0.8f);
        Note late = new Note(2.0, new Voice.Pitch(64), 1.0, 0.8f);

        NoteEdits.Edit back = NoteEdits.move(List.of(early, late), List.of(early, late), -8.0, 0, LOOP);

        assertEquals(List.of(0.0, 2.0), back.notes().stream().map(Note::beat).toList(),
                "the first one cannot go before the start, so neither does the second");
    }

    @Test
    void severalNotesAreTakenAwayAtOnce() {
        Note kept = new Note(0.0, new Voice.Pitch(60), 1.0, 0.8f);
        Note one = new Note(1.0, new Voice.Pitch(62), 1.0, 0.8f);
        Note two = new Note(2.0, new Voice.Pitch(64), 1.0, 0.8f);

        assertEquals(List.of(kept), NoteEdits.remove(List.of(kept, one, two), List.of(one, two)));
    }

    @Test
    void velocityMovesTogetherAndKeepsWhatStandsBetweenTheNotes() {
        Note soft = new Note(0.0, new Voice.Pitch(60), 1.0, 0.4f);
        Note loud = new Note(1.0, new Voice.Pitch(64), 1.0, 0.8f);

        NoteEdits.Edit up = NoteEdits.velocity(List.of(soft, loud), List.of(soft, loud), 0.1f);
        assertEquals(0.5f, up.notes().get(0).velocity(), 1e-6f);
        assertEquals(0.9f, up.notes().get(1).velocity(), 1e-6f);

        NoteEdits.Edit ceiling = NoteEdits.velocity(List.of(soft, loud), List.of(soft, loud), 0.5f);
        assertEquals(1.0f, ceiling.notes().get(1).velocity(), 1e-6f, "the loudest reaches the top");
        assertEquals(0.6f, ceiling.notes().get(0).velocity(), 1e-6f, "and holds the other back with it");
    }

    @Test
    void aNoteIsNeverTurnedAllTheWayDown() {
        Note note = new Note(0.0, new Voice.Pitch(60), 1.0, 0.2f);

        NoteEdits.Edit down = NoteEdits.velocity(List.of(note), List.of(note), -1.0f);

        assertEquals(NoteEdits.QUIETEST, down.notes().getFirst().velocity());
    }

    @Test
    void aBandHoldsEveryNoteItTouches() {
        Note before = new Note(0.0, new Voice.Pitch(60), 1.0, 0.8f);
        Note inside = new Note(4.0, new Voice.Pitch(62), 1.0, 0.8f);
        Note tooHigh = new Note(4.0, new Voice.Pitch(80), 1.0, 0.8f);
        Note reachingIn = new Note(2.0, new Voice.Pitch(61), 3.0, 0.8f);

        List<Note> band = NoteEdits.within(List.of(before, inside, tooHigh, reachingIn), 3.5, 6.0, 55, 70);

        assertEquals(List.of(inside, reachingIn), band.stream().sorted(
                java.util.Comparator.comparingInt(NoteEditsTest::midiNote).reversed()).toList());
    }

    @Test
    void aNoteCanBeTakenAway() {
        Note kept = new Note(0.0, new Voice.Pitch(60), 1.0, 0.8f);
        Note gone = new Note(1.0, new Voice.Pitch(64), 1.0, 0.8f);

        assertEquals(List.of(kept), NoteEdits.remove(List.of(kept, gone), gone));
    }

    @Test
    void aShorterLoopPlaysWhatFitsAndCutsWhatHangsOver() {
        List<Note> notes = List.of(
                new Note(0.0, new Voice.Pitch(60), 2.0, 0.8f),
                new Note(5.0, new Voice.Pitch(62), 4.0, 0.8f),
                new Note(9.0, new Voice.Pitch(64), 1.0, 0.8f));

        List<Note> within = NoteEdits.within(notes, 8.0);

        assertEquals(2, within.size(), "the note past the end is not played");
        assertEquals(2.0, within.get(0).durationBeats());
        assertEquals(3.0, within.get(1).durationBeats(), "cut off where the loop ends");
    }

    @Test
    void aLineBecomesTheChordsOfItsBars() {
        var bars = io.groovebox.model.Progression.parse("Cm Ab");
        List<Note> line = List.of(
                new Note(0.0, new Voice.Pitch(60), 1.0, 0.8f),
                new Note(4.0, new Voice.Pitch(68), 0.5, 0.6f));

        List<Note> chords = NoteEdits.chords(line, bars, 4);

        assertEquals(List.of(60, 63, 67, 68, 72, 75), chords.stream().map(NoteEditsTest::midiNote).toList());
        assertEquals(0.5, chords.get(5).durationBeats(), "each note of a chord as long as the one it came from");
        assertEquals(0.6f, chords.get(3).velocity());
    }

    @Test
    void aNoteTheChordDoesNotHoldIsLeftAsItIs() {
        var bars = io.groovebox.model.Progression.parse("Cm - Cm");
        List<Note> line = List.of(
                new Note(0.0, new Voice.Pitch(62), 1.0, 0.8f),
                new Note(4.0, new Voice.Pitch(60), 1.0, 0.8f),
                new Note(8.0, new Voice.Pitch(60), 1.0, 0.8f));

        List<Note> chords = NoteEdits.chords(line, bars, 4);

        assertEquals(5, chords.size());
        assertEquals(List.of(62, 60, 60, 63, 67), chords.stream().map(NoteEditsTest::midiNote).toList(),
                "a passing note and a bar with no chord stay single; the third bar is a chord");
    }

    @Test
    void withNoChordsNamedALineIsLeftAlone() {
        List<Note> line = List.of(new Note(0.0, new Voice.Pitch(60), 1.0, 0.8f));

        assertEquals(line, NoteEdits.chords(line, io.groovebox.model.Progression.NONE, 4));
    }

    private static int midiNote(Note note) {
        return ((Voice.Pitch) note.voice()).midiNote();
    }
}
