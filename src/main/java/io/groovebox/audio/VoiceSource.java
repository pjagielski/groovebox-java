package io.groovebox.audio;

/**
 * A voice that makes its samples up as it goes, instead of playing a {@link Sample} rendered in
 * advance. The engine asks for one frame at a time, in order, from the frame the note starts on,
 * which is what lets a knob turned now be heard in a note that is already sounding.
 */
public interface VoiceSource {

    /** The next frame of this voice. Called exactly once per frame, in order. */
    float next();

    /**
     * The next frame in both ears. A voice that sounds the same in each — every synth voice here —
     * needs only {@link #next()}; one playing a stereo sample writes the two channels itself.
     */
    default void next(float[] stereoOut) {
        float value = next();
        stereoOut[0] = value;
        stereoOut[1] = value;
    }

    /** True once the note has finished ringing and the engine can drop the voice. */
    boolean finished();
}
