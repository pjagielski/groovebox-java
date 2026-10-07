package io.groovebox.model;

/**
 * One step of a loop's pass: the piece of the audio struck there, in frames, or a rest. A loop with
 * no steps plays straight through, which is what a loop is until somebody rearranges it; a loop
 * with them plays whatever each step names, in the order the steps are in. That is all a rearranged
 * break is - the same slices, struck in another order.
 *
 * <p>A step strikes its piece and lets it ring: a slice longer than the step it sits in runs into
 * the next, as a sampler's one-shot does, and a rest is nothing struck rather than silence imposed.
 */
public record Step(int from, int until) {

    /** Nothing struck here, so whatever is ringing goes on ringing. */
    public static final Step REST = new Step(0, 0);

    public Step {
        if (from < 0 || until < 0) {
            throw new IllegalArgumentException("A step is a piece of the audio, not " + from + ".." + until);
        }
    }

    public boolean rests() {
        return until <= from;
    }
}
