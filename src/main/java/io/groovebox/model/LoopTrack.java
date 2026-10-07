package io.groovebox.model;

import io.groovebox.audio.EffectSource;
import io.groovebox.audio.Sample;

import java.util.List;
import java.util.Objects;

/**
 * A track that plays a piece of recorded audio rather than notes: a break, a vocal, a bar of
 * chords. {@code bars} is how many bars of the jam it is meant to fill, which is all the engine
 * needs to work out how fast to read it — the rate that makes those bars last as long as the jam's
 * do, whatever tempo the jam is at.
 *
 * <p>A loop shorter than the jam's own loop plays again from its start; the engine starts it every
 * {@code bars} bars.
 *
 * <p>{@code steps} is how the pass is laid out when it has been cut up and rearranged: one
 * {@link Step} for each equal part of the pass, each naming the piece of audio struck there. Empty
 * - which is how a loop arrives - means the whole thing struck once, straight through.
 *
 * <p>{@code channel} is what the pass is heard through, or null to go straight to the mix. Unlike
 * the rest of the record it is a thing rather than a value: the studio keeps one per loop track for
 * as long as the track is there, so the tail of a delay survives the jam being published again.
 */
public record LoopTrack(Sample audio, double bars, float gain, List<Step> steps, EffectSource channel)
        implements Track {

    /** A loop played straight through, which is what one is until it is cut up. */
    public LoopTrack(Sample audio, double bars, float gain) {
        this(audio, bars, gain, List.of());
    }

    /** A loop laid out but played dry, straight into the mix. */
    public LoopTrack(Sample audio, double bars, float gain, List<Step> steps) {
        this(audio, bars, gain, steps, null);
    }

    public LoopTrack {
        Objects.requireNonNull(audio, "audio");
        steps = List.copyOf(steps);
        if (!Double.isFinite(bars) || bars <= 0.0) {
            throw new IllegalArgumentException("A loop fills a positive number of bars, not " + bars);
        }
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }

    /** Whether the pass is laid out step by step rather than struck once and left to run. */
    public boolean laidOut() {
        return !steps.isEmpty();
    }

    /** How long one pass lasts, in beats. */
    public double lengthBeats(int beatsPerBar) {
        return bars * beatsPerBar;
    }

    /**
     * The tempo the audio was recorded at, as far as its length and its bars tell: what the studio
     * shows beside a loop, and what says whether the bars given to it are right.
     */
    public double sourceBpm(int sampleRate, int beatsPerBar) {
        double seconds = audio.frameCount() / (double) sampleRate;
        return lengthBeats(beatsPerBar) * 60.0 / seconds;
    }
}
