package io.groovebox.synth;

import io.groovebox.audio.AudioEffect;
import io.groovebox.audio.EffectSource;
import io.groovebox.audio.VoiceSource;

/**
 * A synth whose notes the engine can play as they are made, rather than rendering each note to a
 * {@link io.groovebox.audio.Sample} first. That is the difference between a knob heard
 * from the next loop and a knob heard right now, in the note already sounding.
 */
public interface LivePitchSynth extends PitchSynth, EffectSource {

    /** A voice for one note: {@code heldFrames} long, ringing on for its release after that. */
    VoiceSource voice(int midiNote, int heldFrames, int sampleRate);

    /**
     * What this synth's voices are heard through — a delay, a reverb — or null for none. The engine
     * asks once per session and keeps it, so the tail survives between notes and between loops.
     */
    @Override
    default AudioEffect effects(int sampleRate) {
        return null;
    }
}
