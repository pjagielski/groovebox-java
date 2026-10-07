package io.groovebox.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.groovebox.audio.AudioEngine;
import io.groovebox.audio.Sample;
import io.groovebox.livecode.LiveCodeException;
import io.groovebox.model.Envelope;
import io.groovebox.model.LoopTrack;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Song;
import io.groovebox.model.Step;
import io.groovebox.model.Track;
import io.groovebox.model.Voice;
import io.groovebox.synth.EffectParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {

    private static final Note LINE = new Note(0, new Voice.Pitch(64), 1, 0.8f);

    /** The drum track and one melody of its own notes, played by a pluck: no file, no device. */
    private static Session session() {
        Session session = new Session();
        session.startWith(TrackList.startingWith("Lead", new MelodySource.OwnNotes(List.of(LINE), null),
                Instrument.of("pluck")), Sounds.resolveSynth("pluck"));
        return session;
    }

    /** A MIDI file of a bar of quarter notes on its second track, the first left for the tempo, as a DAW writes one. */
    private static Path midiFile(Path dir) throws Exception {
        javax.sound.midi.Sequence sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        sequence.createTrack();
        javax.sound.midi.Track lead = sequence.createTrack();
        for (int beat = 0; beat < 4; beat++) {
            lead.add(new javax.sound.midi.MidiEvent(
                    new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 57 + beat, 90), beat * 480L));
            lead.add(new javax.sound.midi.MidiEvent(
                    new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_OFF, 0, 57 + beat, 0), beat * 480L + 400));
        }
        Path file = dir.resolve("song.mid");
        javax.sound.midi.MidiSystem.write(sequence, 1, file.toFile());
        return file;
    }

    private static List<Float> gains(Session session) {
        return session.jam().song().tracks().stream().map(Track::gain).toList();
    }

    private static List<Note> melody(Session session) {
        return ((MelodyTrack) session.jam().song().tracks().get(1)).notes();
    }

    @Test
    void theEngineIsHandedTheJamOnceThereIsOne() {
        Session session = new Session();
        assertEquals(null, session.jam());

        session = session();

        assertEquals(List.of(LINE), melody(session));
        assertEquals(8 * Session.BEATS_PER_BAR, ((MelodyTrack) session.jam().song().tracks().get(1)).patternLengthBeats());
    }

    @Test
    void theTempoAndTheLoopAreHeardAtOnce() {
        Session session = session();

        session.setBpm(96);
        session.setBars(2);

        assertEquals(96, session.jam().song().bpm());
        assertEquals(2 * Session.BEATS_PER_BAR, ((MelodyTrack) session.jam().song().tracks().get(1)).patternLengthBeats());
    }

    @Test
    void aSoloSilencesEveryOtherTrackUntilItIsLetGo() {
        Session session = session();
        TrackList tracks = session.tracks();

        tracks.replace(1, tracks.get(1).withSoloed(true));
        session.publish();
        assertEquals(0.0f, gains(session).get(0));
        assertNotEquals(0.0f, gains(session).get(1));

        tracks.replace(1, tracks.get(1).withSoloed(false));
        session.publish();
        assertNotEquals(0.0f, gains(session).get(0));
    }

    @Test
    void muteAndSoloAreHeardAndSaySoTrackByTrack() {
        Session session = session();

        session.setSoloed(0, true);
        assertEquals(List.of(true, false), List.of(session.heard(0), session.heard(1)));
        assertNotEquals(0.0f, gains(session).get(0));
        assertEquals(0.0f, gains(session).get(1), "the drums alone");

        session.setMuted(0, true);
        assertFalse(session.heard(0), "mute still wins over solo");
        assertEquals(0.0f, gains(session).get(0));

        session.setMuted(0, false);
        session.setSoloed(0, false);
        assertTrue(session.heard(1));
        assertNotEquals(0.0f, gains(session).get(1));
    }

    @Test
    void changesMadeTogetherAreHandedOverOnceTheyAreAllMade() {
        Session session = session();
        AudioEngine.Jam before = session.jam();

        session.changing(() -> {
            session.setBpm(150);
            session.setBars(4);
            // half the change is not a jam anyone should hear
            assertSame(before, session.jam());
        });

        assertEquals(150, session.jam().song().bpm());
        assertEquals(4 * Session.BEATS_PER_BAR, ((MelodyTrack) session.jam().song().tracks().get(1)).patternLengthBeats());
    }

    @Test
    void anUndoTakesBackTheNotesButLeavesTheTempoWhereTheHandPutIt() {
        Session session = session();
        session.tracks().select(1);
        Note added = new Note(2, new Voice.Pitch(67), 1, 0.8f);

        session.changeSource(new MelodySource.OwnNotes(List.of(LINE, added), null));
        session.setBpm(100);
        assertEquals(List.of(LINE, added), melody(session));

        assertTrue(session.undo());
        assertEquals(List.of(LINE), melody(session));
        assertEquals(100, session.jam().song().bpm());

        assertTrue(session.redo());
        assertEquals(List.of(LINE, added), melody(session));
        assertFalse(session.redo());
    }

    @Test
    void aJamLoadedStartsAHistoryOfItsOwn(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("jam-test.properties");
        Files.writeString(config, "file=" + midiFile(dir).toAbsolutePath().toString().replace('\\', '/') + "\n");
        Session session = session();
        session.tracks().select(1);
        session.changeSource(new MelodySource.OwnNotes(List.of(), null));

        session.load(config);

        // the line written in the last jam is not this jam's to come back to
        assertFalse(session.undo());
        assertEquals(2, session.tracks().size());
        session.setCode("s(\"bd*4\")");
        session.runCode();
        assertTrue(session.undo());
    }

    @Test
    void codeThatDoesNotParseLeavesTheGridAsItWas() {
        Session session = session();
        session.setCode("s(\"bd*4\")");
        session.runCode();
        List<GridRow> drawn = session.rows();
        assertEquals(1, drawn.size());

        session.setCode("s(\"bd*4\"");

        assertThrows(LiveCodeException.class, session::runCode);
        assertEquals(drawn, session.rows());
    }

    @Test
    void eachRowOfTheGridIsHeardThroughItsOwnChannelWithASynthAndAChannelForEveryTrack() {
        Session session = session();
        session.setCode("$: stack(s(\"bd*4\"), s(\"hh*8\"))");
        session.runCode();

        List<Track> heard = session.jam().song().tracks();

        assertEquals(3, heard.size(), "two rows, then the lead");
        assertSame(session.rows().get(0).channel(), ((MelodyTrack) heard.get(0)).channel());
        assertSame(session.rows().get(1).channel(), ((MelodyTrack) heard.get(1)).channel());
        assertEquals(3, session.jam().synths().size());
        assertEquals(List.of(LINE), ((MelodyTrack) heard.get(2)).notes());
    }

    @Test
    void aRowsKnobsAreHeardInItsNotes() {
        Session session = session();
        session.setCode("s(\"bd ~ bd ~\").gain(\"1 0.5\")");
        session.runCode();

        session.changeRow(0, row -> row.tunedTo(-5).withDecay(0.25).withAccentDepth(1));

        List<Note> kicks = ((MelodyTrack) session.jam().song().tracks().get(0)).notes();
        assertEquals(new Envelope(0.25, 0.0, -5), kicks.get(0).envelope());
        assertEquals(1.0f, kicks.get(0).velocity());
        assertEquals(0.25f, kicks.get(1).velocity(), 1e-6f, "the half-loud one, squared");
    }

    @Test
    void runningTheCodeAgainKeepsTheChannelOfARowItDrawsAgain() {
        Session session = session();
        session.setCode("s(\"bd*4\")");
        session.runCode();
        var channel = session.rows().get(0).channel();
        channel.setParams(EffectParams.DEFAULT.withDelayMix(0.5f));

        session.setCode("s(\"bd*8\")");
        session.runCode();

        assertSame(channel, session.rows().get(0).channel());
        float[] steps = session.rows().get(0).accents();
        int hits = 0;
        for (float step : steps) {
            hits += step > 0 ? 1 : 0;
        }
        assertEquals(8, hits, "with the steps the code says now");
    }

    @Test
    void aClickOnTheGridCyclesThroughTheAccentsAndIsUndone() {
        Session session = session();
        session.setCode("s(\"bd ~ ~ ~\")");
        session.runCode();
        int step = session.rows().getFirst().accents().length - 1;

        session.cycleAccent(0, step);
        assertEquals(0.8f, session.rows().getFirst().accents()[step]);
        session.cycleAccent(0, step);
        assertEquals(1.0f, session.rows().getFirst().accents()[step]);

        session.undo();
        assertEquals(0.8f, session.rows().getFirst().accents()[step]);
    }

    @Test
    void aDelayLockedToTheBeatFollowsTheTempoOnALoopNobodyIsLookingAt() {
        Session session = session();
        StudioTrack.Loop loop = new StudioTrack.Loop("Break", 1.0f, false, Path.of("break.wav"), 2,
                Sample.mono(new float[100])).lockedTo("EIGHTH");
        loop.channel().setParams(EffectParams.DEFAULT.withDelayMix(0.4f));
        session.tracks().add(loop);
        // the drum track on show, so no panel is there to retime the loop's channel
        session.tracks().select(0);

        session.setBpm(120);
        assertEquals(250, loop.channel().params().delayMillis(), 1e-3);

        session.setBpm(60);
        assertEquals(500, loop.channel().params().delayMillis(), 1e-3);
        assertEquals(0.4f, loop.channel().params().delayMix());
    }

    @Test
    void aLoopAddedIsHeardAndItsStepsChangedByTrackAreUndone() {
        Session session = session();
        StudioTrack.Loop loop = new StudioTrack.Loop("Break", 1.0f, false, Path.of("break.wav"), 1,
                Sample.mono(new float[800]));
        StudioTrack.Slicing cut = StudioTrack.Slicing.of(loop.audio(), 1, false, 4, List.of());

        session.addTrack(loop.cutInto(cut));
        // the drum track still selected: the loop is changed by its place, not by the selection
        session.changeLoop(2, loop.cutInto(cut.with(1, 3)));

        LoopTrack heard = (LoopTrack) session.jam().song().tracks().get(2);
        assertEquals(new Step(600, 800), heard.steps().get(1), "step two plays the fourth slice");
        assertTrue(session.undo());
        assertTrue(((LoopTrack) session.jam().song().tracks().get(2)).steps().isEmpty(), "back as recorded");
        assertTrue(session.undo());
        assertEquals(2, session.jam().song().tracks().size(), "and before that, no loop at all");
    }

    @Test
    void aTrackTakenOutIsGoneFromTheJamAndAnUndoBringsItBack() {
        Session session = session();

        session.removeTrack(0);
        assertEquals(2, session.tracks().size(), "the drum track stays: there is one grid");
        session.removeTrack(1);
        assertEquals(1, session.tracks().size());
        assertEquals(1, session.jam().song().tracks().size());

        assertTrue(session.undo());
        assertEquals(List.of(LINE), melody(session));
    }

    @Test
    void anotherDrumPatternRedrawsTheGrid() {
        Session session = session();

        session.usePattern("disco");

        assertEquals(Sounds.drumPatterns().get("disco").size(), session.rows().size());
        assertEquals(session.rows().size() + 1, session.jam().song().tracks().size(), "a track a row, and the lead");
        assertTrue(Session.patternNames().contains("disco"));
        assertThrows(IllegalArgumentException.class, () -> session.usePattern("no such drums"));
    }

    @Test
    void anotherRecordingInALoopKeepsItsChannelLevelAndCut(@TempDir Path dir) throws Exception {
        Path first = wav(dir.resolve("100_4_first.wav"), 0.5f);
        Path second = wav(dir.resolve("100_4_second.wav"), 0.25f);
        Session session = session();
        StudioTrack.Loop loop = session.newLoop(first);
        loop = loop.withGain(0.6f).cutInto(StudioTrack.Slicing.of(loop.audio(), 1, false, loop.bars() * 4, List.of()));
        session.addTrack(loop);

        session.changeLoopFile(2, second);

        StudioTrack.Loop changed = (StudioTrack.Loop) session.tracks().get(2);
        assertEquals(second, changed.file());
        assertEquals("second", changed.name());
        assertSame(loop.channel(), changed.channel());
        assertEquals(0.6f, changed.gain());
        assertEquals(1.0, changed.slicing().beats());
        assertEquals(0.25f, changed.audio().copyMono()[10], 1e-3f);
    }

    @Test
    void aMelodysSoundChangesOnTheSynthTheEngineAlreadyPlays() {
        Instrument instrument = Instrument.of("pluck");
        var synth = instrument.synth();
        var pluck = instrument.setting().params();

        assertTrue(instrument.load("pad"));

        assertSame(synth, instrument.synth());
        assertEquals("pad", instrument.patch());
        assertEquals("pad", instrument.setting().preset());
        assertNotEquals(pluck, instrument.setting().params());
    }

    /** A second of one level, at the engine's rate: as plain a loop as there can be. */
    private static Path wav(Path file, float level) throws Exception {
        int frames = AudioEngine.DEFAULT_SAMPLE_RATE;
        byte[] data = new byte[frames * 2];
        short value = (short) (level * Short.MAX_VALUE);
        for (int frame = 0; frame < frames; frame++) {
            data[2 * frame] = (byte) value;
            data[2 * frame + 1] = (byte) (value >> 8);
        }
        var format = new javax.sound.sampled.AudioFormat(AudioEngine.DEFAULT_SAMPLE_RATE, 16, 1, true, false);
        try (var stream = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(data), format,
                frames)) {
            javax.sound.sampled.AudioSystem.write(stream, javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    @Test
    void aNewMelodyGoesOutOnAChannelAndUnderANameNothingElseHas() {
        Session session = session();
        session.tracks().add(session.newMelody());

        StudioTrack.Melody second = session.newMelody();

        assertEquals("Melody 2", second.name());
        assertEquals(2, second.instrument().channel());
    }

    @Test
    void anEditWaitsForTheNextLoopButAGainDoesNot() {
        Session session = session();
        Song heard = session.jam().song();
        session.tracks().select(1);

        session.tracks().replace(1, session.tracks().get(1).withGain(0.5f));
        session.publish();
        assertFalse(session.waiting(heard));

        session.changeSource(new MelodySource.OwnNotes(List.of(), null));
        assertTrue(session.waiting(heard));
    }
}
