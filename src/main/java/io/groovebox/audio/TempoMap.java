package io.groovebox.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the beats of a live jam fall: the tempo it has been played at, change by change. Each
 * change starts at a frame and at the beat that frame had reached, so moving the tempo re-times
 * every beat still ahead and none behind. Immutable — a new tempo makes a new map — so another
 * thread can read one while the render thread replaces it.
 */
final class TempoMap {
    // enough to reach back past the audio device's buffer while a tempo knob is dragged a block at a time
    private static final int KEPT_CHANGES = 64;

    private record Change(long frame, double beat, double bpm) {
    }

    private final List<Change> changes;
    private final int sampleRate;

    private TempoMap(List<Change> changes, int sampleRate) {
        this.changes = changes;
        this.sampleRate = sampleRate;
    }

    static TempoMap starting(double bpm, int sampleRate) {
        return new TempoMap(List.of(new Change(0, 0.0, checked(bpm))), sampleRate);
    }

    /** This map with {@code bpm} from {@code frame} on; a change already at that frame is replaced. */
    TempoMap at(long frame, double bpm) {
        if (checked(bpm) == bpm() && frame >= last().frame()) {
            return this;
        }
        double beat = beatAt(frame);
        List<Change> next = new ArrayList<>(changes);
        next.removeIf(change -> change.frame() >= frame);
        next.add(new Change(frame, beat, bpm));
        return new TempoMap(List.copyOf(next.subList(Math.max(0, next.size() - KEPT_CHANGES), next.size())),
                sampleRate);
    }

    /** The tempo now: the last change's. */
    double bpm() {
        return last().bpm();
    }

    double bpmAt(long frame) {
        return changeAtFrame(frame).bpm();
    }

    double beatAt(long frame) {
        Change change = changeAtFrame(frame);
        return change.beat() + (frame - change.frame()) * change.bpm() / 60.0 / sampleRate;
    }

    long frameAt(double beat) {
        Change change = changes.getFirst();
        for (Change next : changes) {
            if (next.beat() <= beat) {
                change = next;
            }
        }
        return change.frame() + Math.round((beat - change.beat()) * 60.0 * sampleRate / change.bpm());
    }

    /** How many frames {@code beats} last at the tempo now. */
    long frames(double beats) {
        return Math.round(beats * 60.0 * sampleRate / bpm());
    }

    private Change changeAtFrame(long frame) {
        Change change = changes.getFirst();
        for (Change next : changes) {
            if (next.frame() <= frame) {
                change = next;
            }
        }
        return change;
    }

    private Change last() {
        return changes.getLast();
    }

    private static double checked(double bpm) {
        if (!Double.isFinite(bpm) || bpm <= 0.0) {
            throw new IllegalArgumentException("BPM must be positive and finite");
        }
        return bpm;
    }
}
