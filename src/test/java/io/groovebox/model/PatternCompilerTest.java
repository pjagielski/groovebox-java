package io.groovebox.model;

import io.groovebox.audio.Sample;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatternCompilerTest {

    @Test
    void compilesPatternCharactersToMusicalTimeAndVelocity() {
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X.o.", 0.5f)
        ));

        var notes = PatternCompiler.compile(song);

        assertEquals(List.of(
                new Note(0.0, Drum.KICK, 1.0, 0.5f),
                new Note(2.0, Drum.KICK, 1.0, 0.25f)
        ), notes);
    }

    @Test
    void aLoopOfAudioDoesNotStretchTheJamsOwnLoop() {
        var song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(), 8.0, 1.0f),
                // four bars of audio in a jam whose loop is two: it is cut off and starts again
                new LoopTrack(Sample.mono(new float[100]), 4, 1.0f)
        ));

        assertEquals(8.0, PatternCompiler.totalBeats(song));
    }

    @Test
    void aSongOfNothingButALoopLoopsWithTheLoop() {
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f),
                new LoopTrack(Sample.mono(new float[100]), 2, 1.0f)
        ));

        // nothing written to set the length, so the audio sets it: two bars
        assertEquals(8.0, PatternCompiler.totalBeats(song));
    }

    @Test
    void tilesADrumTrackToMatchALongerMelodyTrack() {
        var pitch = new Voice.Pitch(60);
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f),
                new MelodyTrack(List.of(new Note(0.0, pitch, 1.0, 1.0f)), 8.0, 1.0f)
        ));

        assertEquals(8.0, PatternCompiler.totalBeats(song));

        var notes = PatternCompiler.compile(song);

        assertEquals(List.of(
                new Note(0.0, Drum.KICK, 1.0, 1.0f),
                new Note(0.0, pitch, 1.0, 1.0f),
                new Note(4.0, Drum.KICK, 1.0, 1.0f)
        ), notes);
    }

    @Test
    void cutsADrumBarShortWhenTheLoopEndsInsideIt() {
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X.X.", 1.0f),
                new MelodyTrack(List.of(), 2.0, 1.0f)
        ));

        assertEquals(List.of(new Note(0.0, Drum.KICK, 1.0, 1.0f)), PatternCompiler.compile(song));
    }

    @Test
    void startsTheNextBarWhenTheLoopReachesIntoIt() {
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f),
                new MelodyTrack(List.of(), 5.0, 1.0f)
        ));

        assertEquals(List.of(
                new Note(0.0, Drum.KICK, 1.0, 1.0f),
                new Note(4.0, Drum.KICK, 1.0, 1.0f)
        ), PatternCompiler.compile(song));
    }
}
