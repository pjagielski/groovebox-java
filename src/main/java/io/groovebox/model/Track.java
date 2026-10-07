package io.groovebox.model;

/**
 * One layer of a {@link Song}: a hand-authored drum pattern ({@link DrumTrack}), a pre-loaded
 * phrase of notes ({@link MelodyTrack}, typically read from a MIDI file), or a piece of recorded
 * audio ({@link LoopTrack}). The first two contribute {@link Note}s when the song is compiled —
 * see {@link PatternCompiler} — while a loop has none to give: the engine plays its audio in time
 * rather than striking anything.
 */
public sealed interface Track permits DrumTrack, MelodyTrack, LoopTrack {

    float gain();
}
