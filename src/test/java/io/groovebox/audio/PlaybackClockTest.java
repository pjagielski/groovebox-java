package io.groovebox.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackClockTest {
    private static final int RATE = 44_100;
    private static final long MILLI = 1_000_000L;

    private long position;
    private long nanos;
    private final PlaybackClock clock = new PlaybackClock(() -> position, () -> nanos, RATE);

    @Test
    void movesOnWithTimeBetweenTheStepsTheDeviceReports() {
        assertEquals(0, clock.frameNow());
        nanos = 5 * MILLI;
        assertEquals(220, clock.frameNow());
    }

    @Test
    void takesTheDevicePositionWhenItSteps() {
        clock.frameNow();
        nanos = 7 * MILLI;
        clock.frameNow();
        position = 441;
        assertEquals(441, clock.frameNow());
        nanos = 9 * MILLI;
        assertEquals(441 + 88, clock.frameNow());
    }

    @Test
    void doesNotRunAheadOfADeviceThatStopped() {
        clock.frameNow();
        nanos = 500 * MILLI;
        assertEquals(RATE / 50, clock.frameNow());
    }
}
