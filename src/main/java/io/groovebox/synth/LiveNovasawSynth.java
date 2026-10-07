package io.groovebox.synth;

import io.groovebox.audio.AudioEffect;
import io.groovebox.audio.Sample;
import io.groovebox.audio.VoiceSource;

/**
 * A novasaw patch whose parameters can be changed while it plays. The voices it hands out read
 * {@link #params()} on every frame, so a knob turned now is heard in every note that is sounding,
 * not only in the notes that start after it.
 *
 * <p>The field is volatile and the parameters are a record, so the panel's thread can publish a
 * whole new set of values without the audio thread ever seeing half of them.
 */
public final class LiveNovasawSynth implements LivePitchSynth {

    private volatile SynthParams params;
    private volatile EffectParams effects = EffectParams.DEFAULT;

    public LiveNovasawSynth(SynthParams params) {
        this.params = params;
    }

    /** Starts from a ported patch: {@code new LiveNovasawSynth(new AnthemLeadSynth())}. */
    public LiveNovasawSynth(NovasawSynth patch) {
        this(patch.params());
    }

    public SynthParams params() {
        return params;
    }

    public void setParams(SynthParams params) {
        this.params = params;
    }

    public EffectParams effectParams() {
        return effects;
    }

    public void setEffectParams(EffectParams effects) {
        this.effects = effects;
    }

    @Override
    public AudioEffect effects(int sampleRate) {
        return new SynthEffects(sampleRate, this::effectParams);
    }

    @Override
    public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
        return new NovasawVoice(midiNote, heldFrames, sampleRate, this::params);
    }

    @Override
    public Sample render(int midiNote, int frameCount, int sampleRate) {
        return NovasawSynth.render(midiNote, frameCount, sampleRate, params);
    }
}
