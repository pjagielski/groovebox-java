package io.groovebox.audio;

/**
 * A peak limiter on the master, the last thing before the mix leaves for the device. Until now the
 * mix was clipped hard at the output, which is what a few Performance FX at once - or a loop track,
 * a break being a finished mix rather than one voice - will find quickly.
 *
 * <p>It looks ahead: the mix is held back a couple of milliseconds, and the gain starts coming down
 * the moment a sample too loud for the ceiling enters, so it is already down by the time that
 * sample leaves. That is what lets it catch a transient instead of distorting it. Coming back up is
 * slow enough not to pump, and one gain serves both channels, so a peak on one side does not pull
 * the image over.
 *
 * <p>The gain is set by the loudest sample still in the line, not by the newest one. It has to be:
 * a single loud frame would otherwise nudge the gain for one frame and let the release carry it
 * back up, and the frame would leave the line at full gain - which is the transient the whole
 * arrangement is here to catch.
 */
final class Limiter {

    /** Just under full scale, so the round to sixteen bits has somewhere to go. */
    private static final float CEILING = 0.99f;
    private static final double LOOKAHEAD_SECONDS = 0.002;
    private static final double RELEASE_SECONDS = 0.15;

    private final int channels;
    private final int lookahead;
    private final float[] line;
    // the loudest of each frame in the line, so the loudest of the whole line can be kept
    private final float[] peaks;
    private float windowPeak;
    private final float attackStep;
    private final float releaseCoefficient;
    private float gain = 1.0f;
    private int position;

    Limiter(int sampleRate, int channels) {
        this.channels = channels;
        this.lookahead = Math.max(1, (int) Math.round(sampleRate * LOOKAHEAD_SECONDS));
        this.line = new float[lookahead * channels];
        this.peaks = new float[lookahead];
        // the whole way down within the lookahead, so the gain is there when the sample it is for is
        this.attackStep = 1.0f / lookahead;
        this.releaseCoefficient = (float) (1 - Math.exp(-1.0 / (sampleRate * RELEASE_SECONDS)));
    }

    /** How many frames the mix is held back by, which is what the limiter costs in latency. */
    int lookaheadFrames() {
        return lookahead;
    }

    /** Limits {@code frames} frames of {@code mix} in place. */
    void process(float[] mix, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            int at = frame * channels;
            float loudest = 0;
            for (int channel = 0; channel < channels; channel++) {
                loudest = Math.max(loudest, Math.abs(mix[at + channel]));
            }
            float leaving = peaks[position];
            peaks[position] = loudest;
            if (loudest >= windowPeak) {
                windowPeak = loudest;
            } else if (leaving >= windowPeak) {
                // the loudest has left the line; the next loudest is whatever is still in it
                windowPeak = 0;
                for (float peak : peaks) {
                    windowPeak = Math.max(windowPeak, peak);
                }
            }
            float wanted = windowPeak > CEILING ? CEILING / windowPeak : 1.0f;
            // equal counts as down, or a gain already where it belongs would creep up and over
            if (wanted <= gain) {
                gain = Math.max(wanted, gain - attackStep);
            } else {
                gain += (1 - gain) * releaseCoefficient;
            }
            int oldest = position * channels;
            for (int channel = 0; channel < channels; channel++) {
                float held = line[oldest + channel];
                line[oldest + channel] = mix[at + channel];
                mix[at + channel] = held * gain;
            }
            position = (position + 1) % lookahead;
        }
    }
}
