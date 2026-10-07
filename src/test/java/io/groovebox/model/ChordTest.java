package io.groovebox.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChordTest {

    @Test
    void chordsAreReadTheWayTheyAreWritten() {
        assertEquals(new Chord(0, Chord.Quality.MAJOR), Chord.parse("C"));
        assertEquals(new Chord(0, Chord.Quality.MINOR), Chord.parse("Cm"));
        assertEquals(new Chord(6, Chord.Quality.MINOR_SEVENTH), Chord.parse("F#m7"));
        assertEquals(new Chord(8, Chord.Quality.SUS_FOURTH), Chord.parse("Absus4"));
        assertEquals(new Chord(7, Chord.Quality.DOMINANT_SEVENTH), Chord.parse("G7"));
        assertEquals(new Chord(11, Chord.Quality.MAJOR_SEVENTH), Chord.parse("Bmaj7"));
    }

    @Test
    void aFlatIsTheSharpBesideItAndBFlatIsNotBMinor() {
        assertEquals(new Chord(10, Chord.Quality.MAJOR), Chord.parse("Bb"), "Bb is A#");
        assertEquals(new Chord(11, Chord.Quality.MINOR), Chord.parse("Bm"), "Bm is not B flat");
        assertEquals(new Chord(3, Chord.Quality.MAJOR), Chord.parse("Eb"));
    }

    @Test
    void whatIsNotAChordIsNoChord() {
        assertNull(Chord.parse("H"));
        assertNull(Chord.parse("Cwhatever"));
        assertNull(Chord.parse(""));
        assertNull(Chord.parse(null));
    }

    @Test
    void aChordHoldsItsOwnNotesInEveryOctave() {
        Chord cMinor = Chord.parse("Cm");

        assertTrue(cMinor.holds(60), "C");
        assertTrue(cMinor.holds(63), "Eb");
        assertTrue(cMinor.holds(67), "G");
        assertTrue(cMinor.holds(48), "and C two octaves down");
        assertFalse(cMinor.holds(64), "E is not in C minor");
    }

    @Test
    void aChordOverANoteKeepsThatNoteLowestAndStaysWithinAnOctave() {
        Chord cMinor = Chord.parse("Cm");

        assertEquals(List.of(60, 63, 67), cMinor.over(60), "C, Eb, G");
        assertEquals(List.of(67, 72, 75), cMinor.over(67), "from the fifth: G, C, Eb above it");
        assertEquals(List.of(62, 63, 67, 72), cMinor.over(62), "a note outside the chord keeps its place");
    }

    @Test
    void aProgressionGoesRoundAsTheLoopRunsPastIt() {
        Progression bars = Progression.parse("Cm Ab Eb Bb");

        assertEquals("Cm", bars.at(0, 4).name());
        assertEquals("G#", bars.at(4, 4).name(), "read in flats, given back in the sharps the roll draws");
        assertEquals("G#", bars.at(7.9, 4).name(), "still the second bar");
        assertEquals("D#", bars.at(8, 4).name());
        assertEquals("Cm", bars.at(16, 4).name(), "round again from the fifth bar");
    }

    @Test
    void aBarCanBeLeftWithoutAChord() {
        Progression bars = Progression.parse("Cm - Eb");

        assertEquals("Cm", bars.at(0, 4).name());
        assertNull(bars.at(4, 4));
        assertEquals("D#", bars.at(8, 4).name());
        assertEquals("Cm D#", Progression.parse("Cm | Eb").toString(), "bar lines and commas separate too");
    }

    @Test
    void aHalfTypedNameNamesNothingAtAll() {
        assertTrue(Progression.parse("Cm Ab E#x").isEmpty(), "one word that is no chord and the line is none");
        assertTrue(Progression.parse("").isEmpty());
        assertNull(Progression.NONE.at(0, 4));
    }
}
