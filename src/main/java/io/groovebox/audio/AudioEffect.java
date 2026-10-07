package io.groovebox.audio;

/**
 * Something the synth's output passes through on its way to the mix — a delay, a reverb, a chain of
 * both. Called once per frame, in order, so it can keep a tail of what it has already heard.
 *
 * <p>A stereo pair goes in and a stereo pair comes out. A synth voice is the same in both ears, so
 * for it an effect is where width comes from; a sample played into the channel arrives already
 * wide, and the channel carries it through rather than folding it down.
 *
 * <p>Only the voices of a {@link io.groovebox.synth.LivePitchSynth} go through it; the
 * drums are mixed straight in, as an insert on one channel of a desk would be.
 */
public interface AudioEffect {

    /** Writes the left and right output for one frame into {@code stereoOut}, tails and all. */
    void process(float left, float right, float[] stereoOut);

    /**
     * The sidechain's key: a kick has just landed, on the frame about to be processed. An effect
     * that ducks under it dips now and comes back on its own; one that does not ignores it.
     */
    default void duck() {
    }

    /**
     * Something has been struck into this channel on the frame about to be processed: a note for a
     * synth, a pass or one slice of it for a loop. An effect with an envelope of its own starts it
     * here, which is what lets a filter open on every slice of a break the way it opens on every
     * note of a line; one without an envelope ignores it.
     */
    default void strike() {
    }
}
