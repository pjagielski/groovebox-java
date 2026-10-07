package io.groovebox.app;

import org.junit.jupiter.api.Test;
import io.groovebox.model.Drum;
import io.groovebox.model.Envelope;
import io.groovebox.model.Note;
import io.groovebox.model.Voice;
import io.groovebox.session.GridRow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepsTest {

    private static GridRow row(int steps) {
        return new GridRow("bd", Drum.KICK, new float[steps], 1.0f, 0.25, Envelope.NONE);
    }

    private static Note note(double beat, int pitch, double length) {
        return new Note(beat, new Voice.Pitch(pitch), length, 0.8f);
    }

    @Test
    void aRowOfSixteenIsOnePageAndFollowsTheBeat() {
        GridRow sixteen = row(16);

        assertEquals(-1, Steps.current(sixteen, -1));
        assertEquals(0, Steps.current(sixteen, 0));
        assertEquals(5, Steps.current(sixteen, 1.25));
        // the second bar plays the same row again
        assertEquals(5, Steps.current(sixteen, 5.25));
        assertEquals(0, Steps.page(sixteen, 3.9));
    }

    @Test
    void aRowOfMoreThanSixteenTurnsThePageWithThePlayhead() {
        GridRow thirtyTwo = row(32);

        assertEquals(0, Steps.page(thirtyTwo, 1.9));
        assertEquals(1, Steps.page(thirtyTwo, 2.0));
        assertEquals(17, Steps.current(thirtyTwo, 2.125));
    }

    @Test
    void aMelodysPadsNameTheNotesThatStartOnThemAndMarkTheHolds() {
        List<Note> line = List.of(note(0, 40, 0.5), note(1, 43, 0.25), note(1, 47, 0.25), note(4, 45, 1));

        Pads.Pad[] first = Steps.melody(line, 0);
        Pads.Pad[] second = Steps.melody(line, 1);

        assertEquals("e2", first[0].label());
        assertEquals(Pads.Pad.HELD, first[1]);
        assertEquals(Pads.Pad.EMPTY, first[2]);
        // two notes on one pad: the lower names it
        assertEquals("g2", first[4].label());
        assertEquals("a2", second[0].label());
        assertEquals(Pads.Pad.HELD, second[3]);
        assertEquals(Pads.Pad.EMPTY, second[4]);
    }

    @Test
    void aDrumTheKitHasTwiceIsToldApartByNumber() {
        GridRow kick = new GridRow("1 bd", Drum.KICK, new float[16], 1.0f, 0.25, Envelope.NONE);
        GridRow hats = new GridRow("hh", Drum.CLOSED_HAT, new float[16], 1.0f, 0.25, Envelope.NONE);

        assertEquals(List.of("bd", "hh", "hh 2"), Screen.names(List.of(kick, hats, hats)));
    }

    @Test
    void aClickOnTheScreenPicksTheDrumRowDrawnThere() {
        // three rows, each as tall as it can be up to 26 pixels, from 34 down
        assertEquals(-1, Screen.drumRowAt(20, 3));
        assertEquals(0, Screen.drumRowAt(34, 3));
        assertEquals(1, Screen.drumRowAt(70, 3));
        assertEquals(2, Screen.drumRowAt(111, 3));
        assertEquals(-1, Screen.drumRowAt(113, 3));
    }

    @Test
    void aLoopsPadsNumberItsSlicesMarkTheMovedOnesAndDashTheRests() {
        // twenty steps: the first page full, the second four long
        List<Integer> order = new java.util.ArrayList<>();
        for (int step = 0; step < 20; step++) {
            order.add(step);
        }
        order.set(1, 5);
        order.set(2, -1);

        Pads.Pad[] first = Steps.loop(order, 0);
        Pads.Pad[] second = Steps.loop(order, 1);

        assertEquals(Pads.Pad.hit("1", 0.8f), first[0], "its own slice, as recorded");
        assertEquals(Pads.Pad.hit("6", 1.0f), first[1], "another slice, stronger");
        assertEquals(Pads.Pad.REST, first[2]);
        assertEquals(Pads.Pad.hit("20", 0.8f), second[3]);
        assertEquals(Pads.Pad.OFF, second[4], "past the last step");
        assertEquals(2, Steps.pages(order.size()));
    }

    @Test
    void aLoopsStepFollowsItsOwnPassNotTheJamsLoop() {
        // a two-bar loop cut into 32 steps, in a jam loop that may run longer than it
        assertEquals(-1, Steps.loopStep(-1, 8, 32));
        assertEquals(0, Steps.loopStep(0, 8, 32));
        assertEquals(17, Steps.loopStep(4.3, 8, 32));
        assertEquals(1, Steps.loopStep(8.3, 8, 32), "its second pass starts it over");
    }

    @Test
    void aClickOnTheLoopPicksTheSliceDrawnThere() {
        // 8 steps over 800 frames, slices where the steps start but the fourth moved to a hit
        List<Integer> starts = List.of(0, 100, 200, 330, 400, 500, 600, 700);

        assertEquals(-1, Screen.loopSliceAt(5, 0, 8, 800, starts), "left of the waveform");
        assertEquals(0, Screen.loopSliceAt(16, 0, 8, 800, starts));
        // the screen's waveform runs from 16 to 504: 488 pixels for the 800 frames of one page
        assertEquals(2, Screen.loopSliceAt(16 + 488 * 320 / 800.0, 0, 8, 800, starts));
        assertEquals(3, Screen.loopSliceAt(16 + 488 * 340 / 800.0, 0, 8, 800, starts));
        assertEquals(7, Screen.loopSliceAt(503, 0, 8, 800, starts));
    }

    @Test
    void notesAreNamedInTheOctavesMidiCounts() {
        assertEquals("c4", Steps.nameOf(60));
        assertEquals("a#-1", Steps.nameOf(10));
        assertEquals("e2", Steps.nameOf(40));
    }
}
