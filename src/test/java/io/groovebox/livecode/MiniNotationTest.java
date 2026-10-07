package io.groovebox.livecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MiniNotationTest {

    @Test
    void stepsShareTheCycleEqually() {
        assertEquals(List.of(event(0.0, 0.5, "bd"), event(0.5, 0.5, "sd")),
                MiniNotation.parse("bd sd").events());
    }

    @Test
    void aGroupRepeatedTwicePutsTheSnareOnTheBackbeat() {
        assertEquals(List.of(event(0.25, 0.25, "sd"), event(0.75, 0.25, "sd")),
                MiniNotation.parse("[~  sd]*2").events());
    }

    @Test
    void aCommaLayersSequencesOverTheSameSpan() {
        assertEquals(List.of(event(0.0, 1.0, "bd"), event(0.0, 0.5, "hh"), event(0.5, 0.5, "hh")),
                MiniNotation.parse("[bd, hh*2]").events());
    }

    @Test
    void euclideanRhythmsMatchTidalsBjorklund() {
        assertEquals(List.of(0.0, 3 / 8.0, 6 / 8.0), starts("bd(3,8)"));
        assertEquals(List.of(0.0, 2 / 8.0, 3 / 8.0, 5 / 8.0, 6 / 8.0), starts("bd(5,8)"));
        assertEquals(List.of(1 / 8.0, 3 / 8.0, 6 / 8.0), starts("bd(3,8,5)"));
    }

    @Test
    void valueAtIsTheValueSoundingAtThatPoint() {
        var gains = MiniNotation.parse("[0.2 0.1]*8");

        assertEquals("0.2", gains.valueAt(0.0));
        assertEquals("0.1", gains.valueAt(1 / 16.0));
        assertEquals("0.2", gains.valueAt(2 / 16.0));
        assertNull(MiniNotation.parse("~ bd").valueAt(0.25));
    }

    @Test
    void anUnclosedGroupIsReportedWhereTheTextEnds() {
        var error = assertThrows(LiveCodeException.class, () -> MiniNotation.parse("[bd sd"));

        assertEquals(6, error.position());
    }

    private static MiniNotation.Event event(double start, double duration, String value) {
        return new MiniNotation.Event(start, duration, value);
    }

    private static List<Double> starts(String pattern) {
        return MiniNotation.parse(pattern).events().stream().map(MiniNotation.Event::start).toList();
    }
}
