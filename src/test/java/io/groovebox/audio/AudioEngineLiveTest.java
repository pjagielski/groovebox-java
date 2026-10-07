package io.groovebox.audio;

import io.groovebox.model.Drum;
import io.groovebox.model.DrumTrack;
import io.groovebox.model.Envelope;
import io.groovebox.model.LoopTrack;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Song;
import io.groovebox.model.Step;
import io.groovebox.model.Voice;
import io.groovebox.synth.LivePitchSynth;
import io.groovebox.synth.PitchSynth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioEngineLiveTest {
    private static final int BLOCK = 128;
    // 31 blocks = 3968 frames: two whole 2000-frame loops at 120 BPM, and not a frame of the third.
    private static final int BLOCKS = 31;
    private static final PitchSynth NO_SYNTH = (midiNote, frameCount, sampleRate) -> {
        throw new AssertionError("melody should not be rendered natively");
    };

    @Test
    void anEditLandsOnTheNextLoopBoundary() {
        Song before = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 0.5f)));
        Song after = new Song(120, 4, List.of(new DrumTrack(Drum.SNARE, ".X..", 0.25f)));
        var renderer = engine().liveRenderer(firstThen(jam(before), jam(after)), false);

        float[] left = render(renderer, BLOCKS);

        assertEquals(0.5f, left[0]);
        assertEquals(0.0f, left[500]);
        assertEquals(0.0f, left[2_000]);
        assertEquals(0.25f, left[2_500]);
    }

    @Test
    void drumsTakenNowAreHeardInTheLoopUnderWayInsteadOfTheOnesQueued() {
        Song before = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X.X.", 1.0f)));
        Song after = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 1.0f)));
        var jam = new AtomicReference<>(jam(before));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] first = render(renderer, 1);
        jam.set(jam(after));
        renderer.takeNow();
        float[] rest = render(renderer, 30);

        assertEquals(1.0f, first[0]);
        assertEquals(0.0f, rest[0], "what has played is not played again");
        assertEquals(1.0f, rest[500 - BLOCK], "beat 1, which only the new pattern has, in this loop");
        assertEquals(1.0f, rest[1_000 - BLOCK], "beat 2 once, not the old hit and the new one together");
        assertEquals(1.0f, rest[2_500 - BLOCK], "and the loop after it as the new pattern too");
    }

    @Test
    void aLoopCutUpNowStopsItsPassAndPlaysItsSlicesFromHere() {
        // a beat a quarter of a 2000-frame loop, each beat of it a level of its own: 1, 2, 3, 4
        Sample audio = beats(4, 500);
        Step last = new Step(1_500, 2_000);
        var jam = new AtomicReference<>(jam(new Song(120, 4, List.of(loop(audio, List.of())))));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] first = render(renderer, 1);
        jam.set(jam(new Song(120, 4, List.of(loop(audio, List.of(last, last, last, last))))));
        renderer.takeNow();
        float[] rest = render(renderer, 30);

        assertEquals(1.0f, first[100], 1e-4f, "the pass, as recorded");
        assertEquals(4.0f, rest[600 - BLOCK], 1e-4f, "beat 1 plays the last slice, and the pass is gone");
        assertEquals(4.0f, rest[2_600 - BLOCK], 1e-4f, "and so does the next loop");
    }

    @Test
    void aLoopStraightenedNowPlaysOnFromWhereItsPassWouldBe() {
        Sample audio = beats(4, 500);
        Step last = new Step(1_500, 2_000);
        var jam = new AtomicReference<>(jam(new Song(120, 4, List.of(loop(audio, List.of(last, last, last, last))))));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 1);
        jam.set(jam(new Song(120, 4, List.of(loop(audio, List.of())))));
        renderer.takeNow();
        float[] rest = render(renderer, 30);

        assertEquals(2.0f, rest[600 - BLOCK], 1e-4f, "beat 1 of the recording, not the slice and not silence");
        assertEquals(3.0f, rest[1_100 - BLOCK], 1e-4f);
    }

    @Test
    void aMelodyChangedNowPlaysItsNewNotesInTheLoopUnderWay() {
        var level = new AtomicReference<>(0.25f);
        LivePitchSynth synth = constantLevelSynth(level);
        Song before = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 1.0f)));
        Song after = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(2.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 1.0f)));
        var jam = new AtomicReference<>(new AudioEngine.Jam(before, synth));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 1);
        jam.set(new AudioEngine.Jam(after, synth));
        renderer.takeNow();
        float[] rest = render(renderer, 14);

        assertEquals(0.0f, rest[600 - BLOCK], "the note it no longer has is not played");
        assertEquals(0.25f, rest[1_100 - BLOCK], 1e-6f, "and the one it has now is, in this loop");
    }

    @Test
    void aLoopTakenAwayNowStopsThePassItWasPlaying() {
        Sample audio = beats(4, 500);
        var jam = new AtomicReference<>(jam(new Song(120, 4, List.of(loop(audio, List.of())))));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 1);
        jam.set(jam(new Song(120, 4, List.of(new MelodyTrack(List.of(), 4.0, 1.0f)))));
        renderer.takeNow();
        float[] rest = render(renderer, 14);

        assertEquals(0.0f, rest[600 - BLOCK], 1e-4f, "not the rest of its bar after it is gone");
    }

    /** {@code count} beats of {@code each} frames, the first at 1, the next at 2 and so on. */
    private static Sample beats(int count, int each) {
        float[] data = new float[count * each];
        for (int frame = 0; frame < data.length; frame++) {
            data[frame] = 1 + frame / each;
        }
        return Sample.mono(data);
    }

    /** A one-bar loop through a channel that passes it on as it is: the channel is the loop's identity. */
    private static final EffectSource THROUGH = rate -> (left, right, stereoOut) -> {
        stereoOut[0] = left;
        stereoOut[1] = right;
    };

    private static LoopTrack loop(Sample audio, List<Step> steps) {
        return new LoopTrack(audio, 1, 1.0f, steps, THROUGH);
    }

    @Test
    void aNewTempoIsTakenUpAtOnceInsideTheLoop() {
        Song fast = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 1.0f)));
        Song slow = new Song(60, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 1.0f)));
        var jam = new AtomicReference<>(jam(fast));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] before = render(renderer, 5);
        jam.set(jam(slow));
        float[] after = render(renderer, 26);

        // beat 1 at 120 BPM; the change comes at frame 640, beat 1.28, and beat 2 is 720 frames on
        assertEquals(1.0f, before[500]);
        assertEquals(0.0f, after[1_000 - 640], "no longer 500 frames a beat");
        assertEquals(1.0f, after[1_360 - 640]);
        assertEquals(1.0f, after[2_360 - 640]);
        var position = renderer.positionAt(1_360);
        assertEquals(2.0, position.beat(), 1e-9);
        assertEquals(60.0, position.bpm(), 1e-9);
    }

    @Test
    void aShorterLoopWrapsAtTheFirstMultipleOfItsLengthStillAhead() {
        Song twoBars = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f), new MelodyTrack(List.of(), 8.0, 1.0f)));
        Song oneBar = new Song(120, 4, List.of(
                new DrumTrack(Drum.SNARE, ".X..", 0.25f), new MelodyTrack(List.of(), 4.0, 1.0f)));
        var jam = new AtomicReference<>(jam(twoBars));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 5);
        jam.set(jam(oneBar));
        float[] left = render(renderer, 26);

        // set in the first bar: the loop ends after it, and the second bar's kick never plays
        assertEquals(0.0f, left[2_000 - 640]);
        assertEquals(0.25f, left[2_500 - 640], "the new loop, from frame 2000");
        assertEquals(4.0, renderer.positionAt(2_500).lengthBeats());
    }

    @Test
    void aLongerLoopRunsOnPastTheOldEndWithTheLongerSongsHits() {
        Song oneBar = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f), new MelodyTrack(List.of(), 4.0, 1.0f)));
        Song twoBars = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(5.0, Drum.SNARE, 0.25, 0.25f)), 8.0, 1.0f)));
        var jam = new AtomicReference<>(jam(oneBar));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 5);
        jam.set(jam(twoBars));
        float[] left = render(renderer, 26);

        assertEquals(0.25f, left[2_500 - 640], "beat 5 of the loop that was playing");
        var position = renderer.positionAt(2_500);
        assertEquals(5.0, position.beat(), 1e-9);
        assertEquals(8.0, position.lengthBeats());
    }

    @Test
    void externalNotesNotYetSentFollowANewTempo() {
        var melody = new MelodyTrack(List.of(new Note(3.0, new Voice.Pitch(60), 0.5, 100 / 127f)), 4.0, 1.0f);
        var jam = new AtomicReference<>(outward(new Song(120, 4, List.of(melody))));
        var renderer = engine().liveRenderer(jam::get, true);

        render(renderer, 5);
        assertEquals(1_500, renderer.frameAt(3.0));
        jam.set(outward(new Song(60, 4, List.of(melody))));
        render(renderer, 1);

        assertEquals(2_360, renderer.frameAt(3.0));
    }

    @Test
    void aShorterLoopTakesBackTheExternalNotesPastItsEnd() {
        var early = new Note(1.0, new Voice.Pitch(60), 0.5, 100 / 127f);
        var late = new Note(6.0, new Voice.Pitch(64), 0.5, 100 / 127f);
        var jam = new AtomicReference<>(outward(new Song(120, 4, List.of(new MelodyTrack(List.of(early, late), 8.0, 1.0f)))));
        var renderer = engine().liveRenderer(jam::get, true);

        render(renderer, 1);
        jam.set(outward(new Song(120, 4, List.of(new MelodyTrack(List.of(early), 4.0, 1.0f)))));
        render(renderer, 1);

        assertEquals(List.of(
                new AudioEngine.ExternalNote(1.0, true, 60, 100, 1.0, 0, 0),
                new AudioEngine.ExternalNote(1.5, false, 60, 0, 1.0, 0, 0)
        ), drain(renderer));
    }

    @Test
    void aLoopShorterThanABarPlaysOnlyTheStartOfThePattern() {
        Song halfBar = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X..X", 1.0f),
                new MelodyTrack(List.of(), 2.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(halfBar), false);

        float[] left = render(renderer, BLOCKS);

        assertEquals(1.0f, left[0]);
        assertEquals(0.0f, left[1_500]);
        assertEquals(1.0f, left[1_000]);
        assertEquals(1.0f, left[3_000]);
    }

    @Test
    void aDrumNoteWithAnEnvelopePlaysAShapedCopyOfItsSample() {
        var samples = SampleBank.from(Map.of(Drum.OPEN_HAT, Sample.mono(1.0f, 1.0f, 1.0f, 1.0f)));
        var engine = new AudioEngine(samples, 1_000, BLOCK, 8);
        var hit = new Note(0.0, Drum.OPEN_HAT, 1.0, 1.0f, new Envelope(0.002, 0.0));
        Song song = new Song(120, 4, List.of(new MelodyTrack(List.of(hit), 4.0, 1.0f)));

        float[] left = render(engine.liveRenderer(() -> jam(song), false), BLOCKS);

        assertEquals(1.0f, left[0]);
        assertEquals((float) Math.pow(1000, -0.5), left[1], 1e-6f);
        assertEquals(0.0f, left[2]);
    }

    @Test
    void externalMelodyIsHandedOnInBeatsInsteadOfRendered() {
        var melody = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(60), 0.5, 100 / 127f)), 4.0, 1.0f);
        Song song = new Song(120, 4, List.of(melody));
        var renderer = engine().liveRenderer(() -> outward(song), true);

        float[] left = render(renderer, BLOCKS);

        assertEquals(List.of(
                new AudioEngine.ExternalNote(1.0, true, 60, 100, 1.0, 0, 0),
                new AudioEngine.ExternalNote(1.5, false, 60, 0, 1.0, 0, 0),
                new AudioEngine.ExternalNote(5.0, true, 60, 100, 5.0, 0, 0),
                new AudioEngine.ExternalNote(5.5, false, 60, 0, 5.0, 0, 0)
        ), drain(renderer));
        assertEquals(2_500, renderer.frameAt(5.0));
        for (float sample : left) {
            assertEquals(0.0f, sample);
        }
    }

    @Test
    void eachTrackGoesOutOnItsOwnChannelOrIsPlayedHere() {
        var level = new AtomicReference<>(0.25f);
        var here = new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 1.0f);
        var out = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(64), 0.5, 100 / 127f)), 4.0, 1.0f);
        var bass = new MelodyTrack(List.of(new Note(2.0, new Voice.Pitch(36), 0.5, 100 / 127f)), 4.0, 1.0f);
        Song song = new Song(120, 4, List.of(here, out, bass));
        LivePitchSynth synth = constantLevelSynth(level);
        var renderer = engine().liveRenderer(
                () -> new AudioEngine.Jam(song, List.of(synth), List.of(AudioEngine.Jam.HERE, 2, 5)), true);

        // eight blocks, 1024 frames: past the second track's note at frame 500, short of the next loop
        float[] left = render(renderer, 8);

        assertEquals(0.25f, left[100], 1e-6f, "the first track, played here");
        assertEquals(List.of(
                new AudioEngine.ExternalNote(1.0, true, 64, 100, 1.0, 1, 2),
                new AudioEngine.ExternalNote(1.5, false, 64, 0, 1.0, 1, 2),
                new AudioEngine.ExternalNote(2.0, true, 36, 100, 2.0, 2, 5),
                new AudioEngine.ExternalNote(2.5, false, 36, 0, 2.0, 2, 5)
        ), drain(renderer), "the other two, each on its own channel");
        assertEquals(0.0f, left[500 + 100], "and not played here as well");
    }

    @Test
    void aMutedMelodySendsNothingAndAQuieterOneSendsSofterNotes() {
        var melody = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 0.0f);
        var jam = new AtomicReference<>(outward(new Song(120, 4, List.of(melody))));
        var renderer = engine().liveRenderer(jam::get, true);

        render(renderer, 1);

        // queued all the same, so that unmuting it later in the loop is heard at once
        AudioEngine.ExternalNote noteOn = drain(renderer).getFirst();
        assertEquals(127, noteOn.velocity());
        assertEquals(0, AudioEngine.sentVelocity(noteOn, renderer.trackGain(noteOn.track())), "muted: not sent");
        jam.set(outward(new Song(120, 4, List.of(new MelodyTrack(melody.notes(), 4.0, 0.5f)))));
        render(renderer, 1);
        assertEquals(64, AudioEngine.sentVelocity(noteOn, renderer.trackGain(noteOn.track())));
    }

    @Test
    void aMuteIsHeardAtOnceRatherThanAtTheNextLoop() {
        Song playing = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 1.0f),
                new DrumTrack(Drum.SNARE, "X...", 0.5f)));
        Song muted = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 0.0f),
                new DrumTrack(Drum.SNARE, "X...", 0.5f)));
        var jam = new AtomicReference<>(jam(playing));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] before = render(renderer, 5);
        jam.set(jam(muted));
        float[] after = render(renderer, 26);

        assertEquals(1.0f, before[500]);
        assertEquals(0.0f, after[1_000 - 640], "the next kick, in the same loop");
        assertEquals(0.0f, after[1_500 - 640]);
        assertEquals(0.5f, after[2_000 - 640], "the other track plays on");
    }

    @Test
    void aMutedNoteFallsSilentWhileItSoundsWithoutAClick() {
        var level = new AtomicReference<>(0.25f);
        var note = new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f);
        var jam = new AtomicReference<>(new AudioEngine.Jam(new Song(120, 4, List.of(note)), constantLevelSynth(level)));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] before = render(renderer, 2);
        jam.set(new AudioEngine.Jam(new Song(120, 4, List.of(new MelodyTrack(note.notes(), 4.0, 0.0f))),
                jam.get().synthFor(0)));
        float[] fading = render(renderer, 1);
        float[] after = render(renderer, 1);

        assertEquals(0.25f, before[200], 1e-6f);
        // one block's glide down, not a step: halfway through it, half the level
        assertEquals(0.125f, fading[BLOCK / 2], 1e-3f);
        assertTrue(fading[BLOCK - 1] < 0.01f);
        assertEquals(0.0f, after[10], "the note still held, but silent");
    }

    @Test
    void aLiveSynthIsHeardChangingInsideANoteThatIsAlreadySounding() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(new MelodyTrack(
                List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(
                () -> new AudioEngine.Jam(song, constantLevelSynth(level)), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);
        assertEquals(0.25f, mix[0]);

        level.set(0.75f);
        renderer.renderNext(mix);

        // the same note is still sounding: the new value is heard without waiting for the next loop
        assertEquals(0.75f, mix[0]);
    }

    @Test
    void afterSchedulingStopsTheTailPlaysOnButNoNewNoteStarts() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);
        assertEquals(1.0f, mix[0], "the loop's first kick");

        renderer.stopScheduling();
        float[] left = render(renderer, BLOCKS);

        // the next loop would have put a kick at frame 2000 of this stretch; nothing should arrive
        for (float value : left) {
            assertEquals(0.0f, value);
        }
    }

    @Test
    void aStutterRepeatsTheMixAndStoppingLetsGoOfIt() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.stutter(0.5);

        float[] held = render(renderer, 4);
        assertEquals(1.0f, held[0], 1e-6f, "the kick");
        assertEquals(1.0f, held[250], 1e-6f, "the kick again, an eighth later, from the repeat");

        renderer.stopScheduling();
        float[] after = render(renderer, BLOCKS);
        for (int frame = 16; frame < after.length; frame++) {
            assertEquals(0.0f, after[frame], 1e-6f, "a stopped jam should not keep repeating");
        }
    }

    @Test
    void aStutterPlaysItsNotesAgainThroughTheSynthAsItIsNow() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(new MelodyTrack(
                List.of(new Note(0.0, new Voice.Pitch(60), 0.25, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(
                () -> new AudioEngine.Jam(song, constantLevelSynth(level)), false);
        renderer.stutter(0.5);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);
        assertEquals(0.25f, mix[0], "the note as the song plays it");

        level.set(0.75f);
        float[] left = render(renderer, 3);

        // frame 250 of the render is frame 122 here: the slice's first repeat, with the new level
        assertEquals(0.75f, left[250 - BLOCK], 1e-6f);
        assertEquals(0.0f, left[200 - BLOCK], 1e-6f, "a repeat is cut to the slice, not smeared over it");
    }

    @Test
    void aHeldStutterKeepsItsSliceWhileTheSongMovesOnUnderneath() {
        Song kicks = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 0.5f)));
        // at the same gain as the kicks: a repeat plays at the gain of its track as it is now
        Song snares = new Song(120, 4, List.of(new DrumTrack(Drum.SNARE, ".X..", 0.5f)));
        var renderer = engine().liveRenderer(firstThen(jam(kicks), jam(snares)), false);
        renderer.stutter(0.5);

        float[] left = render(renderer, BLOCKS);

        assertEquals(0.5f, left[2_000], 1e-6f, "still the first loop's kick, in the second loop");
        assertEquals(0.5f, left[2_500], 1e-6f, "and the second loop's snare is not played over it");
    }

    @Test
    void aStutterHeldForManyBarsKeepsRepeating() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 0.5f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.stutter(0.5);

        // twenty seconds at 1000 frames a second: far longer than the song is remembered for
        float[] left = render(renderer, 160);

        assertEquals(0.5f, left[19_750], 1e-6f, "the slice's kick, still repeating an eighth apart");
    }

    @Test
    void lettingGoOfAStutterPicksTheSongUpWhereItHasGot() {
        Song song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 0.5f), new DrumTrack(Drum.SNARE, "..X.", 0.25f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.stutter(0.5);
        float[] held = render(renderer, 4);
        renderer.stutter(0);

        float[] after = render(renderer, 12);

        assertEquals(0.5f, held[250], 1e-6f, "repeating while held");
        assertEquals(0.25f, after[1_000 - 4 * BLOCK], 1e-6f, "the song's snare, on time, after letting go");
        assertEquals(0.0f, after[750 - 4 * BLOCK], 1e-6f, "and no more repeats");
    }

    @Test
    void aPerformanceEffectIsPlayedOverTheDrumsTooAndStopLetsGoOfIt() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.performance().filter(0.0);

        float[] left = render(renderer, BLOCKS);

        // a low-pass smears the one-frame kick out over the frames after it
        assertTrue(left[2_000] < 0.5f, "the kick itself, softened: " + left[2_000]);
        assertTrue(left[2_001] > 0.05f, "and ringing on after it: " + left[2_001]);

        renderer.stopScheduling();
        float[] after = render(renderer, 2);
        assertEquals(0.0f, after[after.length - 1], 1e-6f);
    }

    @Test
    void effectsSitAcrossTheSynthOnly() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f),
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        LivePitchSynth synth = constantLevelSynth(level);
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                return synth.render(midiNote, frameCount, sampleRate);
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return synth.voice(midiNote, heldFrames, sampleRate);
            }

            @Override
            public AudioEffect effects(int sampleRate) {
                return (left, right, stereoOut) -> {
                    stereoOut[0] = left * 2;
                    stereoOut[1] = right * 2;
                };
            }
        }), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);

        // the kick (1.0) went straight to the mix; only the voice's 0.25 was doubled
        assertEquals(1.5f, mix[0], 1e-6f);
        assertEquals(0.5f, mix[2], 1e-6f);
    }

    @Test
    void aVoiceWithTwoChannelsKeepsThemThroughItsBus() {
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, wideSynth()), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);

        // the bus used to read its left channel and send it to both ears, so anything wider than a
        // synth voice arrived folded down; a loop's channel is about to go through the same path
        assertEquals(0.25f, mix[0], 1e-6f);
        assertEquals(-0.75f, mix[1], 1e-6f);
    }

    @Test
    void aWideVoicesEffectsSeeBothOfItsChannels() {
        var seen = new ArrayList<float[]>();
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        LivePitchSynth wide = wideSynth();
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                return wide.render(midiNote, frameCount, sampleRate);
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return wide.voice(midiNote, heldFrames, sampleRate);
            }

            @Override
            public AudioEffect effects(int sampleRate) {
                return (left, right, stereoOut) -> {
                    seen.add(new float[] {left, right});
                    // swapped, so a channel that went nowhere would show as silence on both sides
                    stereoOut[0] = right;
                    stereoOut[1] = left;
                };
            }
        }), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);

        assertArrayEquals(new float[] {0.25f, -0.75f}, seen.get(0), 1e-6f);
        assertEquals(-0.75f, mix[0], 1e-6f);
        assertEquals(0.25f, mix[1], 1e-6f);
    }

    /** A synth whose voice is not the same in both ears, as a stereo sample played into a bus is not. */
    private static LivePitchSynth wideSynth() {
        return new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                throw new AssertionError("a live synth's melody should not be rendered in advance");
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return new VoiceSource() {
                    private int frame;

                    @Override
                    public float next() {
                        throw new AssertionError("this voice writes both channels itself");
                    }

                    @Override
                    public void next(float[] stereoOut) {
                        frame++;
                        stereoOut[0] = 0.25f;
                        stereoOut[1] = -0.75f;
                    }

                    @Override
                    public boolean finished() {
                        return frame >= heldFrames;
                    }
                };
            }
        };
    }

    @Test
    void everyKickReachesTheSynthsEffectsOnItsOwnFrame() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X.X.", 1.0f),
                new DrumTrack(Drum.SNARE, ".X.X", 1.0f),
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        LivePitchSynth synth = constantLevelSynth(level);
        var ducks = new ArrayList<Integer>();
        var processed = new AtomicInteger();
        // one synth for the whole session, as a player hands it: a new one would get a channel of its own
        LivePitchSynth ducking = new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                return synth.render(midiNote, frameCount, sampleRate);
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return synth.voice(midiNote, heldFrames, sampleRate);
            }

            @Override
            public AudioEffect effects(int sampleRate) {
                return new AudioEffect() {
                    @Override
                    public void process(float left, float right, float[] stereoOut) {
                        processed.incrementAndGet();
                        stereoOut[0] = left;
                        stereoOut[1] = right;
                    }

                    @Override
                    public void duck() {
                        ducks.add(processed.get());
                    }
                };
            }
        };
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, ducking), false);

        render(renderer, BLOCKS);

        // the kicks only, never the snares, each one just before the frame it lands on
        assertEquals(List.of(0, 1_000, 2_000, 3_000), ducks);
    }

    @Test
    void everyTrackIsPlayedByItsOwnSynthThroughItsOwnEffects() {
        LivePitchSynth lead = amplifiedSynth(0.25f, 2);
        LivePitchSynth bass = amplifiedSynth(0.125f, 4);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(72), 1.0, 1.0f)), 4.0, 1.0f),
                new MelodyTrack(List.of(new Note(2.0, new Voice.Pitch(36), 1.0, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, List.of(lead, bass)), false);

        float[] left = render(renderer, BLOCKS);

        assertEquals(0.5f, left[200], 1e-6f, "the lead, doubled by its own effects");
        assertEquals(0.5f, left[1_200], 1e-6f, "the bass, four times over by its own, not the lead's");
    }

    @Test
    void aKeyPressedWhileTheJamIsStoppedIsHeardThroughItsSynthsOwnEffects() {
        LivePitchSynth synth = amplifiedSynth(0.25f, 2);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 1.0, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().idleRenderer(() -> new AudioEngine.Jam(song, synth));

        float[] quiet = render(renderer, 3);
        renderer.audition(synth, 72, 0.2, 1.0f);
        float[] heard = render(renderer, 3);

        assertEquals(0.0f, quiet[100], "an idle renderer plays no loop of its own");
        assertEquals(0.5f, heard[10], 1e-6f, "the key, doubled by that synth's own effects");
    }

    @Test
    void anAuditionedNoteIsHeardOverAJamThatIsPlaying() {
        LivePitchSynth synth = amplifiedSynth(0.25f, 1);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 0.25, 1.0f)), 4.0, 0.0f)));
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, synth), false);

        float[] muted = render(renderer, 2);
        renderer.audition(synth, 72, 0.2, 1.0f);
        float[] heard = render(renderer, 2);

        assertEquals(0.0f, muted[100], "the track is muted, so the loop itself is silent");
        assertEquals(0.25f, heard[10], 1e-6f, "a key is listened to, whatever the fader says");
    }

    @Test
    void aSynthTheJamHasLetGoOfClosesItsChannelOnceItsTailIsOver() {
        LivePitchSynth first = amplifiedSynth(0.25f, 1);
        LivePitchSynth second = amplifiedSynth(0.25f, 1);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 1.0f)));
        var jam = new AtomicReference<>(new AudioEngine.Jam(song, first));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 5);
        jam.set(new AudioEngine.Jam(song, second));
        render(renderer, 20);

        // the second loop, from frame 2000, is the new synth's; the first falls quiet at 250 and goes
        assertEquals(1, renderer.busCount());
    }

    @Test
    void aLoopIsReadAtWhateverRateMakesItsBarsTheJamsBars() {
        // 2000 frames at 1000 a second is two seconds: one bar at 120 BPM exactly
        Sample audio = ramp(2000);
        Song atItsOwnTempo = new Song(120, 4, List.of(new LoopTrack(audio, 1, 1.0f)));
        Song twiceAsFast = new Song(240, 4, List.of(new LoopTrack(audio, 1, 1.0f)));

        float[] asRecorded = render(engine().liveRenderer(() -> jam(atItsOwnTempo), false), 8);
        float[] hurried = render(engine().liveRenderer(() -> jam(twiceAsFast), false), 8);

        assertEquals(audio.copyMono()[400], asRecorded[400], 1e-4f, "at its own tempo, frame for frame");
        assertEquals(audio.copyMono()[800], hurried[400], 1e-4f, "at twice the tempo, twice through the sample");
    }

    @Test
    void aLoopIsStretchedByAFractionOfAFrameAsReadilyAsByAWholeOne() {
        // a click on each of four beats of a bar 2000 frames long, which is a bar at 120 BPM
        float[] clicks = new float[2000];
        for (int click = 0; click < 4; click++) {
            clicks[click * 500] = 1.0f;
        }
        // played at 160, where a bar is 1500 frames and a beat 375: the rate wanted is 1 and a third
        Song song = new Song(160, 4, List.of(new LoopTrack(Sample.mono(clicks), 1, 1.0f),
                new MelodyTrack(List.of(), 8.0, 1.0f)));

        float[] left = render(engine().liveRenderer(() -> jam(song), false), 16);

        for (int beat = 0; beat < 4; beat++) {
            assertTrue(left[beat * 375] > 0.9f, "the click on beat " + beat + " of the jam");
        }
        // and nothing where the clicks would be if the sample were read a frame at a time
        assertEquals(0.0f, left[500], 1e-3f, "no click where an unstretched loop would put one");
    }

    @Test
    void aLoopShorterThanTheJamsLoopPlaysAgainFromItsStart() {
        Sample audio = ramp(2000);
        // one bar of loop against a melody eight beats long: the loop comes round twice
        Song song = new Song(120, 4, List.of(new LoopTrack(audio, 1, 1.0f),
                new MelodyTrack(List.of(), 8.0, 1.0f)));

        float[] left = render(engine().liveRenderer(() -> jam(song), false), 32);

        assertEquals(audio.copyMono()[1900], left[1900], 1e-4f, "the end of the first pass");
        assertEquals(audio.copyMono()[100], left[2100], 1e-4f, "and the start of the second");
    }

    @Test
    void theOtherTracksAreStillHeardWhileAShorterLoopComesRound() {
        // silent audio, so anything heard at all is the drums and not the loop
        Sample silence = Sample.mono(new float[2000]);
        // one bar of loop under a jam eight beats long: the loop starts twice within the one loop
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 0.5f),
                new LoopTrack(silence, 1, 1.0f), new MelodyTrack(List.of(), 8.0, 1.0f)));

        float[] left = render(engine().liveRenderer(() -> jam(song), false), 32);

        // a kick on every beat, 500 frames apart, none of them waiting for the loop to come round
        for (int beat = 0; beat < 8; beat++) {
            assertEquals(0.5f, left[beat * 500], "the kick on beat " + beat);
        }
    }

    @Test
    void givingALoopMoreBarsThanTheJamsLoopLeavesItPlaying() {
        float[] flat = new float[2000];
        Arrays.fill(flat, 0.4f);
        Sample audio = Sample.mono(flat);
        // the jam's loop is eight beats, set by what is written; the loop is given four bars of it
        var jam = new AtomicReference<>(jam(new Song(120, 4,
                List.of(new LoopTrack(audio, 1, 1.0f), new MelodyTrack(List.of(), 8.0, 1.0f)))));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 8);
        jam.set(jam(new Song(120, 4,
                List.of(new LoopTrack(audio, 4, 1.0f), new MelodyTrack(List.of(), 8.0, 1.0f)))));
        float[] after = render(renderer, 56);

        // a pass too long for the jam's loop is cut off at its end and started again, never dropped
        assertTrue(after[3_000] > 0.3f, "still playing a loop later");
        assertTrue(after[6_000] > 0.3f, "and a loop after that");
    }

    @Test
    void aStutterTakenInTheMiddleOfALoopTakesTheLoopBackWithIt() {
        // a bar of loop with a click on each of its first three beats, each quieter than the last
        float[] clicks = new float[2000];
        clicks[0] = 1.0f;
        clicks[500] = 0.8f;
        clicks[1_000] = 0.6f;
        Song song = new Song(120, 4, List.of(new LoopTrack(Sample.mono(clicks), 1, 1.0f),
                new MelodyTrack(List.of(), 8.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        // two beats in, where the loop has just played its third click
        render(renderer, 8);
        renderer.stutter(1);
        float[] after = render(renderer, 16);

        // the slice starts on beat 2, so every repeat takes the loop back to that click
        assertEquals(0.6f, after[1_500 - 1_024], 1e-3f, "the first repeat");
        assertEquals(0.6f, after[2_000 - 1_024], 1e-3f, "the second");
        assertEquals(0.6f, after[2_500 - 1_024], 1e-3f, "the third");
    }

    @Test
    void lettingGoOfAStutterPutsTheLoopBackWhereTheJamHasGot() {
        float[] clicks = new float[2000];
        clicks[0] = 1.0f;
        clicks[500] = 0.8f;
        clicks[1_000] = 0.6f;
        Song song = new Song(120, 4, List.of(new LoopTrack(Sample.mono(clicks), 1, 1.0f),
                new MelodyTrack(List.of(), 8.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        render(renderer, 8);
        renderer.stutter(1);
        // held to frame 2432, which is beat 4.864: most of the way through the loop's second pass
        render(renderer, 11);
        renderer.stutter(0);
        float[] after = render(renderer, 8);

        // the loop picks up where the jam has got to, so its clicks land on the beat again
        assertEquals(0.8f, after[2_500 - 2_432], 1e-3f, "the click due on beat 5");
        assertEquals(0.6f, after[3_000 - 2_432], 1e-3f, "and the one due on beat 6");
    }

    @Test
    void aPieceOfALoopIsHeardOnItsOwnAndStopsWhereItEnds() {
        Sample audio = ramp(2_000);
        float[] climbing = audio.copyMono();
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "....", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        // frames 500 to 700 of it, read one for one
        renderer.audition(audio, 500, 700, 1.0, 1.0f, null);
        float[] left = render(renderer, 4);

        assertEquals(climbing[500], left[0], 1e-4f, "from where it was asked for");
        assertEquals(climbing[600], left[100], 1e-4f, "and on from there");
        assertEquals(0.0f, left[300], 1e-4f, "and nothing past the end of the piece");
    }

    @Test
    void aSliceTriedByHandIsHeardThroughTheLoopsOwnChannel() {
        Sample audio = ramp(2_000);
        float[] climbing = audio.copyMono();
        EffectSource channel = rate -> (left, right, stereoOut) -> {
            stereoOut[0] = left * 3;
            stereoOut[1] = right * 3;
        };
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "....", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        renderer.audition(audio, 500, 700, 1.0, 1.0f, channel);
        float[] left = render(renderer, 4);

        // a cut is judged by ear, so what is tried has to be the sound that will be played
        assertEquals(climbing[500] * 3, left[0], 1e-4f, "from where it was asked for, driven");
        assertEquals(climbing[600] * 3, left[100], 1e-4f, "and on from there");
    }

    @Test
    void whatIsTriedNextTakesThePlaceOfWhatWasTriedBefore() {
        Sample audio = ramp(2_000);
        float[] climbing = audio.copyMono();
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "....", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        renderer.audition(audio, 0, 2_000, 1.0, 1.0f, null);
        render(renderer, 2);
        renderer.audition(audio, 1_000, 1_200, 1.0, 1.0f, null);
        float[] after = render(renderer, 4);

        // only the second: clicking about the waveform does not pile one loop on another
        assertEquals(climbing[1_100], after[100], 1e-4f, "the piece asked for second");
    }

    @Test
    void aLaidOutPassPlaysItsStepsInTheOrderTheyAreIn() {
        // four quarters of a bar, each at a level of its own, so which one sounded can be told
        float[] quarters = new float[2_000];
        for (int frame = 0; frame < quarters.length; frame++) {
            quarters[frame] = 0.1f + 0.1f * (frame / 500);
        }
        List<Step> steps = List.of(new Step(1_500, 2_000), new Step(0, 500), Step.REST, new Step(500, 1_000));
        Song song = new Song(120, 4, List.of(new LoopTrack(Sample.mono(quarters), 1, 1.0f, steps)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        float[] left = render(renderer, 16);

        assertEquals(0.4f, left[100], 1e-4f, "the fourth quarter, put first");
        assertEquals(0.1f, left[600], 1e-4f, "then the first");
        assertEquals(0.0f, left[1_200], 1e-4f, "then nothing struck, and the one before it is over");
        assertEquals(0.2f, left[1_700], 1e-4f, "then the second");
    }

    @Test
    void aStepStrikesItsPieceAndLetsItRingIntoTheNext() {
        float[] quarters = new float[2_000];
        for (int frame = 0; frame < quarters.length; frame++) {
            quarters[frame] = 0.1f + 0.1f * (frame / 500);
        }
        // a step a beat long, but the piece in it is a beat and a half: it runs on into the rest
        List<Step> steps = List.of(new Step(0, 750), Step.REST, Step.REST, Step.REST);
        Song song = new Song(120, 4, List.of(new LoopTrack(Sample.mono(quarters), 1, 1.0f, steps)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        float[] left = render(renderer, 16);

        assertEquals(0.2f, left[600], 1e-4f, "past the step it began in, still ringing");
        assertEquals(0.0f, left[800], 1e-4f, "and over where its own piece ends");
    }

    @Test
    void aLoopEndsWithTheJamRatherThanPlayingOutItsPass() {
        // a bar of loop, which at 120 BPM lasts 2000 frames: the stop comes 500 frames into it
        Song song = new Song(120, 4, List.of(new LoopTrack(ramp(2000), 1, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        float[] playing = render(renderer, 4);
        renderer.stopScheduling();
        float[] stopped = render(renderer, 4);

        assertTrue(playing[400] > 0.1f, "the loop is playing when the stop comes");
        // a note in its release is a tail worth ringing on; three more bars of a break is not
        assertEquals(0.0f, stopped[300], "and is over well inside the block after it");
    }

    @Test
    void aStereoLoopKeepsItsTwoChannelsAllTheWayToTheMix() {
        float[] hardLeft = new float[2000];
        float[] hardRight = new float[2000];
        Arrays.fill(hardLeft, 0.5f);
        Arrays.fill(hardRight, -0.5f);
        Song song = new Song(120, 4, List.of(new LoopTrack(Sample.stereo(hardLeft, hardRight), 1, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        float[] mix = new float[BLOCK * 2];
        renderer.renderNext(mix);

        // a block is 128 frames; the tenth of them is well inside the loop
        assertEquals(0.5f, mix[10 * 2], 1e-4f, "one ear");
        assertEquals(-0.5f, mix[10 * 2 + 1], 1e-4f, "and the other, as they were recorded");
    }

    @Test
    void aLoopWithAChannelIsHeardThroughItsEffects() {
        float[] hardLeft = new float[2000];
        float[] hardRight = new float[2000];
        Arrays.fill(hardLeft, 0.5f);
        Arrays.fill(hardRight, -0.25f);
        // the two ears told apart on the way through, so a channel that folded them would show
        EffectSource channel = rate -> (left, right, stereoOut) -> {
            stereoOut[0] = left * 2;
            stereoOut[1] = right * 4;
        };
        Song song = new Song(120, 4, List.of(new LoopTrack(Sample.stereo(hardLeft, hardRight), 1, 1.0f,
                List.of(), channel)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        float[] mix = new float[BLOCK * 2];
        renderer.renderNext(mix);

        assertEquals(1.0f, mix[10 * 2], 1e-4f, "one ear through the channel");
        assertEquals(-1.0f, mix[10 * 2 + 1], 1e-4f, "and the other, still its own");
    }

    @Test
    void aDrumTrackWithAChannelIsHeardThroughItsEffectsAndNotDryAsWell() {
        var struck = new ArrayList<Integer>();
        EffectSource channel = rate -> new AudioEffect() {
            private int frame;

            @Override
            public void process(float left, float right, float[] stereoOut) {
                stereoOut[0] = left * 3;
                stereoOut[1] = right * 3;
                frame++;
            }

            @Override
            public void strike() {
                struck.add(frame);
            }
        };
        Song song = new Song(120, 4, List.of(new MelodyTrack(
                List.of(new Note(0.0, Drum.KICK, 0.25, 1.0f), new Note(1.0, Drum.SNARE, 0.25, 0.5f)), 4.0, 1.0f,
                channel)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        float[] left = render(renderer, 8);

        // three times the kick's one frame of 1.0, and not 1.0 more beside it from a dry copy
        assertEquals(3.0f, left[0], 1e-6f);
        assertEquals(1.5f, left[500], 1e-6f, "the snare, a beat on, through the same channel");
        // struck at each hit, so a filter envelope in it opens with the drum
        assertEquals(List.of(0, 500), struck);
    }

    @Test
    void aLoopsChannelKeepsRingingOnceTheLoopItselfHasGoneQuiet() {
        // an effect with a tail of its own: the loudest thing it has heard, held for ever after
        EffectSource holding = rate -> new AudioEffect() {
            private float held;

            @Override
            public void process(float left, float right, float[] stereoOut) {
                held = Math.max(held, left);
                stereoOut[0] = held;
                stereoOut[1] = held;
            }
        };
        float[] steady = new float[2000];
        Arrays.fill(steady, 0.5f);
        Sample audio = Sample.mono(steady);
        var jam = new AtomicReference<>(jam(new Song(120, 4,
                List.of(new LoopTrack(audio, 1, 1.0f, List.of(), holding)))));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] heard = render(renderer, 3);
        // faded out rather than stopped: the pass plays on into the channel at nothing
        jam.set(jam(new Song(120, 4, List.of(new LoopTrack(audio, 1, 0.0f, List.of(), holding)))));
        float[] quiet = render(renderer, 3);

        assertEquals(0.5f, heard[200], 1e-4f, "the loop reaches its channel");
        assertEquals(0.5f, quiet[300], 1e-4f,
                "and the channel runs on every block, or its tail would stop with the loop");
    }

    @Test
    void aSilencedTrackIsStillSilentInASessionStartedAfterAStop() {
        // what solo amounts to by the time the engine sees it: the other tracks handed over at 0
        Song soloed = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 0.0f),
                new DrumTrack(Drum.SNARE, "X...", 1.0f)));
        var first = engine().liveRenderer(() -> jam(soloed), false);
        float[] playing = render(first, 2);
        first.stopScheduling();

        // Stop leaves the old session ringing and Play makes a new one over the same jam
        var second = engine().liveRenderer(() -> jam(soloed), false);
        float[] again = render(second, 2);

        assertEquals(1.0f, playing[0], 1e-4f, "the soloed track alone, first time round");
        assertEquals(1.0f, again[0], 1e-4f, "and alone again after a stop and a start");
    }

    @Test
    void everySliceOfALaidOutPassStrikesTheLoopsChannel() {
        var struckAt = new ArrayList<Integer>();
        var frame = new AtomicInteger();
        EffectSource counting = rate -> new AudioEffect() {
            @Override
            public void process(float left, float right, float[] stereoOut) {
                frame.incrementAndGet();
                stereoOut[0] = left;
                stereoOut[1] = right;
            }

            @Override
            public void strike() {
                struckAt.add(frame.get());
            }
        };
        // a pass of one bar laid out as four steps, the third of them striking nothing
        List<Step> steps = List.of(new Step(0, 500), new Step(500, 1_000), Step.REST,
                new Step(1_500, 2_000));
        // the empty melody is what sets the jam's loop to the pass's own length: what is written
        // sets it, and a loop on its own would restart under a loop of no length at all
        Song song = new Song(120, 4, List.of(new LoopTrack(ramp(2_000), 1, 1.0f, steps, counting),
                new MelodyTrack(List.of(), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);

        // at this engine's thousand frames a second, a beat is 500 frames and the four-beat pass is
        // 2000; fifteen blocks of 128 stop just short of the pass coming round again
        render(renderer, 15);

        assertEquals(List.of(0, 500, 1500), struckAt,
                "one strike a step, on the beat, and none where the pass rests");
    }

    @Test
    void aMutedLoopFallsSilentWhileItIsPlaying() {
        Sample audio = ramp(2000);
        var jam = new AtomicReference<>(jam(new Song(120, 4, List.of(new LoopTrack(audio, 1, 1.0f)))));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] heard = render(renderer, 3);
        jam.set(jam(new Song(120, 4, List.of(new LoopTrack(audio, 1, 0.0f)))));
        float[] quiet = render(renderer, 3);

        assertTrue(heard[200] > 0.05f, "playing: " + heard[200]);
        assertEquals(0.0f, quiet[300], 1e-4f, "a loop is faded like any other track, while it plays");
    }

    /** A sample that climbs from nothing to one, so where it is read from can be told from what comes out. */
    private static Sample ramp(int frames) {
        float[] climbing = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            climbing[frame] = frame / (float) frames;
        }
        return Sample.mono(climbing);
    }

    /** A synth whose voices play a constant {@code level}, through effects that multiply it by {@code factor}. */
    private static LivePitchSynth amplifiedSynth(float level, float factor) {
        return new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                throw new AssertionError("a live synth's melody should not be rendered in advance");
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return new VoiceSource() {
                    private int frame;

                    @Override
                    public float next() {
                        frame++;
                        return level;
                    }

                    @Override
                    public boolean finished() {
                        return frame >= heldFrames;
                    }
                };
            }

            @Override
            public AudioEffect effects(int sampleRate) {
                return (left, right, stereoOut) -> {
                    stereoOut[0] = left * factor;
                    stereoOut[1] = right * factor;
                };
            }
        };
    }

    /** A synth whose voices simply play whatever {@code level} says at that frame. */
    private static LivePitchSynth constantLevelSynth(AtomicReference<Float> level) {
        return new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                throw new AssertionError("a live synth's melody should not be rendered in advance");
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return new VoiceSource() {
                    private int frame;

                    @Override
                    public float next() {
                        frame++;
                        return level.get();
                    }

                    @Override
                    public boolean finished() {
                        return frame >= heldFrames;
                    }
                };
            }
        };
    }

    private static AudioEngine engine() {
        var samples = SampleBank.from(Map.of(
                Drum.KICK, Sample.mono(1.0f),
                Drum.SNARE, Sample.mono(1.0f)
        ));
        return new AudioEngine(samples, 1_000, BLOCK, 8);
    }

    /** A jam whose tracks all go out to an external synth, on channel 1. */
    private static AudioEngine.Jam outward(Song song) {
        return new AudioEngine.Jam(song, List.of(NO_SYNTH), List.of(0));
    }

    private static AudioEngine.Jam jam(Song song) {
        return new AudioEngine.Jam(song, NO_SYNTH);
    }

    private static <T> Supplier<T> firstThen(T first, T rest) {
        var calls = new AtomicInteger();
        return () -> calls.getAndIncrement() == 0 ? first : rest;
    }

    /** What the renderer has queued for an external synth, in the order it would be sent. */
    private static List<AudioEngine.ExternalNote> drain(AudioEngine.LiveRenderer renderer) {
        var sent = new ArrayList<AudioEngine.ExternalNote>();
        AudioEngine.ExternalNote note;
        while ((note = renderer.externalMelody().poll()) != null) {
            sent.add(note);
        }
        return sent;
    }

    private static float[] render(AudioEngine.LiveRenderer renderer, int blocks) {
        float[] left = new float[blocks * BLOCK];
        float[] mix = new float[BLOCK * 2];
        for (int block = 0; block < blocks; block++) {
            renderer.renderNext(mix);
            for (int frame = 0; frame < BLOCK; frame++) {
                left[block * BLOCK + frame] = mix[frame * 2];
            }
        }
        return left;
    }
}
