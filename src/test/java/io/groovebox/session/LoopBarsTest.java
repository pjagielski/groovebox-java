package io.groovebox.session;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopBarsTest {

    private static final int RATE = 44100;

    /** Four seconds of audio: two bars at 120, four at 240, one at 60. */
    private static final long FOUR_SECONDS = 4 * RATE;

    @Test
    void aBreakNamedForItsTempoAndLengthIsTakenAtItsWord() {
        assertEquals(4, LoopBars.guess(Path.of("loops/100_16_am-volo.wav"), FOUR_SECONDS, RATE, 4, 128));
        assertEquals(2, LoopBars.guess(Path.of("170_8_think.wav"), FOUR_SECONDS, RATE, 4, 128));
    }

    @Test
    void aFileNamedForNothingIsTakenAtTheTempoTheJamIsAt() {
        // four seconds is two bars at 120, and would be four at 240
        assertEquals(2, LoopBars.guess(Path.of("break.wav"), FOUR_SECONDS, RATE, 4, 120));
        assertEquals(4, LoopBars.guess(Path.of("break.wav"), FOUR_SECONDS, RATE, 4, 240));
    }

    @Test
    void aGuessLandsOnALengthALoopIsActuallyCutTo() {
        // 2.3 bars is nothing: it is nearer two than four, in how far it would have to be stretched
        assertEquals(2, LoopBars.nearest(2.3));
        assertEquals(4, LoopBars.nearest(3.1));
        assertEquals(0.5, LoopBars.nearest(0.6));
        assertEquals(32, LoopBars.nearest(100));
    }

    @Test
    void aFileOfNoLengthIsTakenAsOneBarRatherThanNone() {
        assertEquals(1, LoopBars.guess(Path.of("empty.wav"), 0, RATE, 4, 120));
    }

    @Test
    void barsAreWrittenWholeWhereTheyCanBe() {
        assertEquals("4", LoopBars.label(4));
        assertEquals("0.5", LoopBars.label(0.5));
        assertEquals("0.25", LoopBars.label(0.25));
    }
}
