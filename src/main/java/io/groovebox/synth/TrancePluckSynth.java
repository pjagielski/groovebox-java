package io.groovebox.synth;

/**
 * "Trance Pluck - Classic", after the novasaw patch (Sandbox/novasaw, Source/MainComponent.cpp,
 * recipe index 1, factory preset intensity=0.58 tone=0.64 space=0.54 motion=0.28), re-voiced once
 * the filter had an envelope of its own.
 *
 * <p>The port used to sit the filter at 5.5 kHz and ride it on the level's envelope, with a
 * sustain of 0.18: bright all the way through and buzzing on under a held note — a short note
 * rather than a pluck. Now the filter rests low, a few hundred hertz, and its own envelope throws
 * it wide open for an instant and shuts it again within about a tenth of a second; the level dies
 * away to nothing more slowly behind it. That is the pluck: a bright snap, then a darker tail.
 */
public final class TrancePluckSynth extends NovasawSynth {

    public TrancePluckSynth() {
        super(
                0.001f, 0.450f, 0.0f, 0.180f,
                7.0f, 22.0f,
                100.0f, 0.0f, 8500.0f, 35.0f,
                3.0f, 1.2f,
                0.10f, 1.10f, 0.698f,
                0.58f, 0.64f, 0.28f, 0.0f,
                0.001f, 0.160f, 0.0f, 0.100f
        );
    }
}
