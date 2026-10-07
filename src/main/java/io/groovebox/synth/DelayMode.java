package io.groovebox.synth;

/**
 * How a repeat travels between the ears.
 *
 * <p>{@code MONO} is the plain echo: both sides hear the same repeat at the same moment.
 * {@code PING_PONG} sends the dry signal into the left line only and crosses the two lines'
 * feedback, so repeats bounce left-right-left. {@code STEREO} gives each side its own line and its
 * own time — the right at two thirds of the left — which sets up a cross-rhythm rather than a
 * bounce. {@code TAPE} is one line per side at the same time, with the read position drifting the
 * way a tape machine's does, so the repeats warble and detune slightly as they fade.
 */
public enum DelayMode {

    MONO("Mono"), PING_PONG("Ping-pong"), STEREO("Stereo"), TAPE("Tape");

    /** The right line's share of the left line's time in {@link #STEREO}. */
    public static final float STEREO_RATIO = 2.0f / 3;

    /** How far the tape drifts, as a share of the delay time, and how often. */
    static final float TAPE_DEPTH = 0.004f;
    static final float TAPE_RATE_HZ = 0.37f;

    private final String label;

    DelayMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
