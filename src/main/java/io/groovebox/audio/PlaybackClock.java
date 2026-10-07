package io.groovebox.audio;

import java.util.function.LongSupplier;

/**
 * The frame being played right now. An audio device reports its position in steps - 10 ms with
 * DirectSound on Windows - so between two steps this clock moves on with the system clock, and a
 * note due mid-step is not held back until the next one.
 */
final class PlaybackClock {
    private final LongSupplier devicePosition;
    private final LongSupplier nanoTime;
    private final int sampleRate;
    // a device that stops playing stops stepping too, and the clock must not run on without it
    private final long maxFramesPastStep;
    private long lastPosition = -1;
    private long lastStepNanos;

    PlaybackClock(LongSupplier devicePosition, LongSupplier nanoTime, int sampleRate) {
        this.devicePosition = devicePosition;
        this.nanoTime = nanoTime;
        this.sampleRate = sampleRate;
        this.maxFramesPastStep = sampleRate / 50;
    }

    long frameNow() {
        long position = devicePosition.getAsLong();
        long now = nanoTime.getAsLong();
        if (position != lastPosition) {
            lastPosition = position;
            lastStepNanos = now;
        }
        long sinceStep = (now - lastStepNanos) * sampleRate / 1_000_000_000L;
        return position + Math.min(sinceStep, maxFramesPastStep);
    }
}
