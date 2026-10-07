package io.groovebox.synth;

/**
 * "Wide Pad - Halo", after the novasaw patch (Sandbox/novasaw, Source/MainComponent.cpp, recipe
 * index 2, factory preset intensity=0.44 tone=0.48 space=0.84 motion=0.52), but re-voiced for this
 * port. novasaw's pad got its width from a stereo pan spread and its body from a reverb send, both
 * dropped here; left as it was, it came out narrow, dull and several times quieter than the other
 * patches, and its slow swell never opened on notes shorter than a beat.
 *
 * <p>So the saws spread wider (about 20 cents instead of 7), a little sub gives it a floor, the
 * filter sits higher with more of the envelope on it, the swell is quicker (120 ms rather than
 * 380 ms) and the release shorter, so fast phrases do not smear into one another, and there is
 * enough drive to stand level with the lead. Still slower, softer and darker than
 * {@link AnthemLeadSynth}.
 */
public final class WidePadSynth extends NovasawSynth {

    public WidePadSynth() {
        super(
                0.120f, 1.000f, 0.85f, 0.700f,
                12.0f, 22.0f,
                700.0f, 2800.0f, 1680.0f, 28.0f,
                0.18f, 2.5f,
                0.35f, 0.50f, 0.535f,
                0.44f, 0.48f, 0.52f,
                0.20f
        );
    }
}
