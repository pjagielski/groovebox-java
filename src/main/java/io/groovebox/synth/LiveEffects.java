package io.groovebox.synth;

import io.groovebox.audio.AudioEffect;
import io.groovebox.audio.EffectSource;

/**
 * A channel with nothing playing it: the effects a track is heard through, and the settings they
 * read, without a synth attached. What a loop is given, since a loop makes its own sound and needs
 * only somewhere to be heard through.
 *
 * <p>It lives as long as the track does, so the engine keeps one bus for it and a delay's repeats
 * survive the studio publishing the jam again. Like {@link LiveNovasawSynth}, the field is volatile
 * and the settings are a record, so the panel's thread can replace the whole set of values without
 * the audio thread ever seeing half of them.
 */
public final class LiveEffects implements EffectSource {

    private volatile EffectParams params;

    public LiveEffects() {
        this(EffectParams.DRY);
    }

    public LiveEffects(EffectParams params) {
        this.params = params;
    }

    public EffectParams params() {
        return params;
    }

    public void setParams(EffectParams params) {
        this.params = params;
    }

    @Override
    public AudioEffect effects(int sampleRate) {
        return new SynthEffects(sampleRate, this::params);
    }
}
