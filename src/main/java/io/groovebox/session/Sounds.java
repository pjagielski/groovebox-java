package io.groovebox.session;

import io.groovebox.midi.MidiFileReader;
import io.groovebox.model.Drum;
import io.groovebox.model.DrumTrack;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.synth.AcidBassSynth;
import io.groovebox.synth.AnthemLeadSynth;
import io.groovebox.synth.ChordsSynth;
import io.groovebox.synth.PitchSynth;
import io.groovebox.synth.SubBassSynth;
import io.groovebox.synth.TrancePluckSynth;
import io.groovebox.synth.WidePadSynth;

import javax.sound.midi.Sequence;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a jam can name: the synth patches a melody is played by, and the drum patterns a grid is
 * drawn from. Both are made here, in code, rather than shipped: the patterns are written as steps,
 * one character a sixteenth, and the drums themselves are synthesized unless {@code samples/}
 * has a file for them.
 */
public final class Sounds {
    private static final double BEATS_PER_BAR = 4.0;

    private static final Map<String, PitchSynth> SYNTHS = Map.of(
            "anthem", new AnthemLeadSynth(),
            "pad", new WidePadSynth(),
            "chords", new ChordsSynth(),
            "pluck", new TrancePluckSynth(),
            "sub", new SubBassSynth(),
            "acid", new AcidBassSynth()
    );

    // X a strong hit, x a hit, o a soft one, . a rest
    private static final Map<String, List<DrumTrack>> DRUM_PATTERNS = Map.of(
            // four on the floor, the hats on every eighth and softer in between
            "disco", List.of(
                    new DrumTrack(Drum.KICK, "X...X...X...X...", 1.00f),
                    new DrumTrack(Drum.CLOSED_HAT, "x.x.x.x.x.x.x.x.", 0.50f),
                    new DrumTrack(Drum.CLOSED_HAT, ".x.x.x.x.x.x.x.x", 0.25f)),
            // the clap on two and four and an open hat on every off-beat, kept low so a lead sits on top
            "house", List.of(
                    new DrumTrack(Drum.KICK, "X...X...X...X...", 0.50f),
                    new DrumTrack(Drum.CLAP, "....X.......X..x", 0.50f),
                    new DrumTrack(Drum.SNARE, "....X.......X...", 0.38f),
                    new DrumTrack(Drum.OPEN_HAT, "..x...o...x...o.", 0.25f),
                    new DrumTrack(Drum.CLOSED_HAT, ".o.o.o.o.o.o.o.o", 0.20f)),
            // four on the floor with the hats pushing against it
            "four-floor", List.of(
                    new DrumTrack(Drum.KICK, "X...X...X...X...", 1.00f),
                    new DrumTrack(Drum.SNARE, "....X.......X...", 0.80f),
                    new DrumTrack(Drum.CLOSED_HAT, "...X..X....X..X.", 0.55f),
                    new DrumTrack(Drum.OPEN_HAT, "..X...X...X...X.", 0.45f)),
            // a kick that skips, the snare on two and four
            "hip-hop", List.of(
                    new DrumTrack(Drum.KICK, "X.....X.X.......", 1.00f),
                    new DrumTrack(Drum.SNARE, "....X.......X...", 0.85f),
                    new DrumTrack(Drum.CLOSED_HAT, "....X.......X...", 0.45f),
                    new DrumTrack(Drum.OPEN_HAT, "..X.......X.....", 0.55f)),
            // the kick three-three-two, the snare late
            "dancehall", List.of(
                    new DrumTrack(Drum.KICK, "o..o....o..o....", 1.00f),
                    new DrumTrack(Drum.CLOSED_HAT, "o.ooo.o.o.o.oooo", 0.60f),
                    new DrumTrack(Drum.SNARE, "......o.......o.", 0.80f))
    );

    private Sounds() {
    }

    /** The synths a jam can name, in the order of their names. */
    public static List<String> synthNames() {
        return SYNTHS.keySet().stream().sorted().toList();
    }

    public static PitchSynth resolveSynth(String name) {
        PitchSynth synth = SYNTHS.get(name.toLowerCase(Locale.ROOT));
        if (synth == null) {
            throw new IllegalArgumentException("Unknown synth \"" + name + "\", expected one of " + synthNames());
        }
        return synth;
    }

    /** The named drum patterns a jam can draw its grid from ({@code drums=} in a jam's config). */
    public static Map<String, List<DrumTrack>> drumPatterns() {
        return DRUM_PATTERNS;
    }

    /** A window of {@code bars} bars from {@code startBar} on of a MIDI file's track; {@code bars} may be a fraction of a bar. */
    public static MelodyTrack loadMelodyTrack(Sequence sequence, int trackIndex, int startBar, double bars) {
        double startBeat = startBar * BEATS_PER_BAR;
        double patternLength = bars * BEATS_PER_BAR;
        double endBeat = startBeat + patternLength;
        List<Note> notes = MidiFileReader.readTrack(sequence, trackIndex).stream()
                .filter(note -> note.beat() >= startBeat && note.beat() < endBeat)
                // cut to the end of the window, because this is a loop: a note held past the end
                // would go on sounding over the next pass, on top of that pass's own notes, and a
                // file's last note is often held for a long time
                .map(note -> new Note(note.beat() - startBeat, note.voice(),
                        Math.min(note.durationBeats(), endBeat - note.beat()), note.velocity()))
                .toList();
        return new MelodyTrack(notes, patternLength, 1.0f);
    }
}
