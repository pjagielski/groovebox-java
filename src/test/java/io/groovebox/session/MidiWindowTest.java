package io.groovebox.session;

import io.groovebox.model.Voice;
import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MidiWindowTest {
    private static final Path FILE = Path.of("phrase.mid");

    @Test
    void aPickupAtBeatThreePointSevenFiveStartsThePhraseAndRepeatsEveryFourBeats() throws Exception {
        var sequence = new Sequence(Sequence.PPQ, 480);
        var track = sequence.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 56, 100), 1800));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 56, 0), 1860));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 61, 100), 2160));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 61, 0), 2220));
        var window = new MidiWindow(FILE, 0, 0, 2, true).withBeatRange(3.75, 7.75);

        var notes = window.notes(sequence, 8);

        assertEquals(List.of(0.0, 0.75, 4.0, 4.75), notes.stream().map(n -> n.beat()).toList());
        assertEquals(56, ((Voice.Pitch) notes.getFirst().voice()).midiNote());
        assertEquals(0.125, notes.getFirst().durationBeats());
    }

    @Test
    void fractionalBoundariesClipHeldNotesAndPreserveTheExactRangeWhenMoved() throws Exception {
        var window = new MidiWindow(FILE, 0, 0, 2, false).withBeatRange(3.75, 7.75);
        var notes = window.notes(phrase(), 16);

        assertEquals(List.of(0.0, 2.25), notes.stream().map(n -> n.beat()).toList());
        assertEquals(List.of(1.25, 1.75), notes.stream().map(n -> n.durationBeats()).toList());
        assertEquals(3.75, window.withFile(Path.of("other.mid")).withTrackIndex(1).startBeat());
        assertEquals(7.75, window.withFile(Path.of("other.mid")).withTrackIndex(1).endBeat());
        assertThrows(IllegalArgumentException.class, () -> window.withBeatRange(Double.NaN, 8));
        assertThrows(IllegalArgumentException.class, () -> window.withBeatRange(4, Double.POSITIVE_INFINITY));
    }

    private static Sequence phrase() throws Exception {
        var sequence = new Sequence(Sequence.PPQ, 480);
        var track = sequence.createTrack();
        // A sustained note crosses the first boundary; another crosses the last one.
        for (int[] note : List.of(new int[]{3, 60, 2}, new int[]{6, 64, 4}, new int[]{8, 67, 1})) {
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, note[1], 100), note[0] * 480L));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, note[1], 0), (note[0] + note[2]) * 480L));
        }
        return sequence;
    }

    @Test
    void anExplicitRangeRepeatsWithoutTakingNotesPastItsEnd() throws Exception {
        var notes = new MidiWindow(FILE, 0, 1, 2, true).notes(phrase(), 12);

        assertEquals(List.of(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), notes.stream().map(n -> n.beat()).toList());
        assertEquals(List.of(1.0, 2.0, 1.0, 2.0, 1.0, 2.0), notes.stream().map(n -> n.durationBeats()).toList());
        assertEquals(List.of(60, 64, 60, 64, 60, 64), notes.stream()
                .map(n -> ((Voice.Pitch) n.voice()).midiNote()).toList());
    }

    @Test
    void turningLoopingOffPlaysTheExcerptOncePerJamPass() throws Exception {
        var notes = new MidiWindow(FILE, 0, 1, 2, false).notes(phrase(), 12);

        assertEquals(List.of(0.0, 2.0), notes.stream().map(n -> n.beat()).toList());
        assertEquals(List.of(1.0, 2.0), notes.stream().map(n -> n.durationBeats()).toList());
    }

    @Test
    void theLastRepeatIsCutAtTheJamBoundary() throws Exception {
        var notes = new MidiWindow(FILE, 0, 1, 2, true).notes(phrase(), 7);

        assertEquals(List.of(0.0, 2.0, 4.0, 6.0), notes.stream().map(n -> n.beat()).toList());
        assertEquals(1, notes.getLast().durationBeats());
    }

    @Test
    void aLongerExcerptDoesNotMakeTheJamLonger() throws Exception {
        var notes = new MidiWindow(FILE, 0, 0, 4, true).notes(phrase(), 4);

        assertEquals(1, notes.size());
        assertEquals(3, notes.getFirst().beat());
        assertEquals(1, notes.getFirst().durationBeats());
    }

    @Test
    void existingWindowsStillFollowTheJamLength() throws Exception {
        var window = new MidiWindow(FILE, 0, 0);

        assertEquals(1, window.notes(phrase(), 4).size());
        assertEquals(3, window.notes(phrase(), 12).size());
    }

    @Test
    void movingToAnotherFileOrTrackKeepsTheRangeAndRepeat() {
        var window = new MidiWindow(FILE, 1, 4, 8, false);

        assertEquals(new MidiWindow(Path.of("another.mid"), 2, 5, 8, false),
                window.withFile(Path.of("another.mid")).withTrackIndex(2).withStartBar(5));
        assertThrows(IllegalArgumentException.class, () -> new MidiWindow(FILE, 0, 4, 4, true));
        assertThrows(IllegalArgumentException.class, () -> new MidiWindow(FILE, 0, 4, -1, true));
    }
}
