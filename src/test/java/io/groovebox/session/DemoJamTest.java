package io.groovebox.session;

import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Song;
import io.groovebox.model.Voice;
import io.groovebox.synth.DelaySync;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoJamTest {

    private static Session demo() {
        Session session = new Session();
        DemoJam.startIn(session);
        return session;
    }

    private static int pitch(Note note) {
        return ((Voice.Pitch) note.voice()).midiNote();
    }

    @Test
    void theDemoIsADrumKitALeadAPadAndABassWithNothingToUndo() {
        Session session = demo();

        assertEquals(List.of("Drums", "Lead", "Pad", "Bass"),
                session.tracks().tracks().stream().map(StudioTrack::name).toList());
        assertTrue(session.tracks().get(0) instanceof StudioTrack.Drums);
        assertEquals(Sounds.drumPatterns().get("disco").size(), session.rows().size());
        assertEquals(120, session.bpm());
        assertEquals(8, session.bars());
        assertNull(session.config(), "made in code, not read");
        assertFalse(session.undo());
    }

    @Test
    void theEngineIsHandedEveryNoteOverTheWholeLoop() {
        Session session = demo();

        Song heard = session.jam().song();
        // a track a drum row, then the lead, the pad and the bass
        int lead = session.rows().size();
        assertEquals(32, ((MelodyTrack) heard.tracks().get(lead)).patternLengthBeats());
        assertEquals(DemoJam.lead().size(), ((MelodyTrack) heard.tracks().get(lead)).notes().size());
        assertEquals(DemoJam.BARS * 4, ((MelodyTrack) heard.tracks().get(lead + 1)).notes().size());
        assertEquals(DemoJam.BARS * 4, ((MelodyTrack) heard.tracks().get(lead + 2)).notes().size());
    }

    @Test
    void theLeadEchoesOnTheDottedEighthAtTheJamsTempo() {
        Session session = demo();

        StudioTrack.Melody lead = (StudioTrack.Melody) session.tracks().get(1);
        assertEquals(DelaySync.DOTTED_EIGHTH.name(), lead.instrument().setting().delaySync());
        assertEquals(375, lead.instrument().setting().effects().delayMillis(), 1e-3, "three sixteenths at 120");
        assertTrue(lead.instrument().setting().effects().delayMix() > 0);
    }

    @Test
    void thePadDucksUnderTheKick() {
        StudioTrack.Melody pad = (StudioTrack.Melody) demo().tracks().get(2);

        assertTrue(pad.instrument().setting().effects().duckDepth() > 0);
    }

    @Test
    void theLeadIsPlayedOnTheSixteenthsRatherThanTyped() {
        List<Note> lead = DemoJam.lead();

        assertTrue(lead.stream().map(Note::velocity).distinct().count() > 2, "uneven hands");
        for (Note note : lead) {
            double offGrid = note.beat() % 0.25;
            boolean offBeat = Math.round((note.beat() - offGrid) / 0.25) % 2 == 1;
            assertEquals(offBeat ? DemoJam.SWING : 0, offGrid, 1e-9, "the off-beat sixteenths a little late");
        }
        for (int i = 1; i < lead.size(); i++) {
            assertTrue(lead.get(i).beat() > lead.get(i - 1).beat(), "one note at a time, in order");
        }
    }

    @Test
    void theLineSingsOnlyTheNotesOfItsChord() {
        for (int bar = 0; bar < DemoJam.BARS; bar++) {
            List<Integer> chord = java.util.Arrays.stream(DemoJam.CHORDS[bar % DemoJam.CHORDS.length])
                    .map(pitch -> pitch % 12).boxed().toList();
            for (int pitch : DemoJam.LINE[bar]) {
                assertTrue(chord.contains(pitch % 12), "bar " + (bar + 1) + ": " + pitch);
            }
        }
    }

    @Test
    void aLineLoweredByItsChordKeepsToTheChordAndItsShape() {
        int[] am9 = DemoJam.CHORDS[0];

        assertEquals(69, DemoJam.lower(76, am9, 3), "E down past C and B to A");
        assertEquals(64, DemoJam.lower(76, am9, 5), "five notes of a five-note chord: an octave");
        for (int bar = 0; bar < DemoJam.BARS; bar++) {
            int[] chord = DemoJam.CHORDS[bar % DemoJam.CHORDS.length];
            int[] line = DemoJam.LINE[bar];
            for (int i = 1; i < line.length; i++) {
                assertEquals(Integer.signum(line[i] - line[i - 1]),
                        Integer.signum(DemoJam.lower(line[i], chord, DemoJam.LOWERED)
                                - DemoJam.lower(line[i - 1], chord, DemoJam.LOWERED)),
                        "bar " + (bar + 1) + " goes the way it was written");
            }
        }
    }

    @Test
    void theLeadSitsInTheMiddleWithThePedalUnderTheLine() {
        List<Note> lead = DemoJam.lead();

        assertTrue(lead.stream().mapToInt(DemoJamTest::pitch).max().orElseThrow() <= 79, "no higher than G5");
        for (int bar = 0; bar < DemoJam.BARS - 1; bar++) {
            double from = bar * Session.BEATS_PER_BAR;
            List<Note> inBar = lead.stream()
                    .filter(note -> note.beat() >= from && note.beat() < from + Session.BEATS_PER_BAR).toList();
            int pedal = inBar.stream().filter(note -> note.durationBeats() < DemoJam.SUNG)
                    .mapToInt(DemoJamTest::pitch).max().orElseThrow();
            int line = inBar.stream().filter(note -> note.durationBeats() >= DemoJam.SUNG)
                    .mapToInt(DemoJamTest::pitch).min().orElseThrow();
            assertTrue(pedal < line, "bar " + (bar + 1));
        }
    }

    @Test
    void theSecondTimeRoundAnswersRatherThanRepeats() {
        for (int bar = 0; bar < DemoJam.CHORDS.length; bar++) {
            assertNotEquals(java.util.Arrays.toString(DemoJam.LINE[bar]),
                    java.util.Arrays.toString(DemoJam.LINE[bar + DemoJam.CHORDS.length]), "bar " + (bar + 5));
        }
    }

    @Test
    void theLastBarRunsUpAloneWithoutThePedal() {
        double lastBar = (DemoJam.BARS - 1) * Session.BEATS_PER_BAR;
        List<Note> last = DemoJam.lead().stream().filter(note -> note.beat() >= lastBar).toList();

        assertEquals(DemoJam.ACCENTS.length, last.size());
        for (int i = 1; i < last.size(); i++) {
            assertTrue(pitch(last.get(i)) > pitch(last.get(i - 1)), "climbing");
        }
    }

    @Test
    void theLineRingsAndThePedalTicks() {
        assertTrue(DemoJam.SUNG > 2 * DemoJam.TICK);
        double firstBar = Session.BEATS_PER_BAR;
        List<Note> bar = DemoJam.lead().stream().filter(note -> note.beat() < firstBar).toList();
        float line = (float) bar.stream().filter(note -> note.durationBeats() >= DemoJam.SUNG)
                .mapToDouble(Note::velocity).min().orElseThrow();
        float pedal = (float) bar.stream().filter(note -> note.durationBeats() < DemoJam.SUNG)
                .mapToDouble(Note::velocity).max().orElseThrow();
        assertTrue(line > pedal, "the line above the pedal");
    }

    @Test
    void thePadMovesByStepsFromChordToChordAndRoundTheLoop() {
        List<Note> pad = DemoJam.pad();
        int voices = pad.size() / DemoJam.BARS;

        for (int bar = 0; bar < DemoJam.BARS; bar++) {
            int next = (bar + 1) % DemoJam.BARS;
            for (int voice = 0; voice < voices; voice++) {
                int from = pitch(pad.get(bar * voices + voice));
                int to = pitch(pad.get(next * voices + voice));
                assertTrue(Math.abs(to - from) <= 2, "bar " + (bar + 1) + ", voice " + (voice + 1));
            }
        }
    }

    @Test
    void theBassSitsLowAndHeldBetweenTheKicksAndLeadsBackIntoA() {
        List<Note> bass = DemoJam.bass();

        for (Note note : bass) {
            assertEquals(0.5, note.beat() % 1, 1e-9, "on the off-beat");
            assertTrue(note.durationBeats() >= 0.45, "held up to the next kick");
            assertTrue(pitch(note) <= 38, "no higher than D2");
        }
        // the last note a semitone under the first, so the loop comes round into it
        assertEquals(pitch(bass.get(0)) - 1, pitch(bass.get(bass.size() - 1)));
    }

    @Test
    void thePadHoldsEachChordUntilTheNext() {
        for (Note note : DemoJam.pad()) {
            assertEquals(0, note.beat() % Session.BEATS_PER_BAR, 1e-9);
            assertTrue(note.durationBeats() > 3.5 && note.durationBeats() < Session.BEATS_PER_BAR);
        }
    }
}
