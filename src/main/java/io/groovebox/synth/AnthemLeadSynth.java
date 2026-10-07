package io.groovebox.synth;

/**
 * "Anthem Lead - Mainstage" patch (Sandbox/novasaw, Source/MainComponent.cpp, recipe index 0,
 * factory preset intensity=0.72 tone=0.68 space=0.62 motion=0.34 — "space" unused, see
 * {@link NovasawSynth}). Bright, driven, fast attack.
 */
public final class AnthemLeadSynth extends NovasawSynth {

    public AnthemLeadSynth() {
        super(
                0.006f, 0.350f, 0.82f, 0.110f,
                4.0f, 18.0f,
                1800.0f, 7600.0f, 1200.0f, 45.0f,
                5.2f, 4.0f,
                0.15f, 1.30f, 0.520f,
                0.72f, 0.68f, 0.34f
        );
    }
}
