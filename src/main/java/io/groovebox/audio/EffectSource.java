package io.groovebox.audio;

/**
 * Something with a channel of its own: whatever it plays is summed apart from the rest of the mix
 * and goes through its own {@link AudioEffect} on the way in, so two tracks' delays and reverbs are
 * two, each with its own tail.
 *
 * <p>The engine keeps one channel per source and finds it by identity, so a source is a thing that
 * lives as long as the track does — a live synth, a loop's channel — rather than a value rebuilt
 * every time the studio publishes a jam.
 */
public interface EffectSource {

    /** What this channel is heard through — a delay, a reverb — or null for none. */
    AudioEffect effects(int sampleRate);
}
