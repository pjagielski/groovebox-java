package io.groovebox.synth;

/**
 * Everything the synth panel shows for one patch: the preset it started from, its knobs, its
 * effects and the division its delay is locked to. Kept by whoever plays several patches through
 * one panel, to put back when its patch is on the panel again.
 */
public record SynthSetting(String preset, SynthParams params, EffectParams effects, String delaySync) {

    /** A patch as it comes: its own knobs, and the effects with nothing turned up. */
    public static SynthSetting initial(String preset, SynthParams params) {
        return new SynthSetting(preset, params, EffectParams.DEFAULT, DelaySync.FREE.name());
    }

    /**
     * The same setting at another tempo: a delay locked to a division of the beat takes the time
     * that division lasts at {@code bpm}; a free one keeps its own.
     */
    public SynthSetting at(double bpm) {
        return new SynthSetting(preset, params, DelaySync.named(delaySync).timed(effects, bpm), delaySync);
    }
}
