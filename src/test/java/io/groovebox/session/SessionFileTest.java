package io.groovebox.session;

import io.groovebox.model.*;
import io.groovebox.synth.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.nio.charset.StandardCharsets;
import javax.sound.midi.*;
import io.groovebox.audio.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionFileTest {
    @TempDir Path directory;

    @Test
    void sessionsSavedBeforeTheMixerOpenWithAnUnmutedUnityMaster() throws Exception {
        Session before = new Session(); before.startEmpty();
        Path file = directory.resolve("old-session.properties"); before.save(file);
        var properties = SessionFile.readProperties(file);
        properties.remove("master.gain"); properties.remove("master.muted");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { properties.store(writer, "Old version 1 session"); }
        Session after = new Session(); after.startEmpty();
        after.setMasterGain(0.2f); after.setMasterMuted(true);
        after.open(file);
        assertEquals(1, after.masterGain());
        assertFalse(after.masterMuted());
        after.setMasterMuted(true); after.startEmpty();
        assertEquals(1, after.masterGain()); assertFalse(after.masterMuted());
    }

    @Test
    void theWholeDemoAndEveryInstrumentSettingSurviveARoundTrip() throws Exception {
        Session before = new Session();
        DemoJam.startIn(before);
        before.setBpm(93);
        before.setBars(3.75);
        before.setMasterGain(0.37f);
        before.setMasterMuted(true);
        before.setScale(new Scale(3, Scale.Mode.MINOR));
        before.setCode("bd sd hh");
        before.setMuted(2, true);
        before.setSoloed(3, true);
        before.tracks().select(3);
        before.changeRow(0, row -> row.withAccent(1, 0.7f).withDecay(0.2).tunedTo(-2));
        before.rows().getFirst().channel().setParams(EffectParams.DRY.withCrush(0.3f));
        var bass = ((StudioTrack.Melody)before.tracks().get(3)).instrument();
        bass.set(new SynthSetting("sub", bass.setting().params().withVoices(2).withWaveform(Waveform.SQUARE)
                .withCutoff(333).withResonance(0.4f), bass.setting().effects().withDelayMix(0.31f), "FREE"));
        bass.setMidi(4, 71, 55);
        bass.setExternal(true);
        Path file = directory.resolve("sesja.properties");
        before.save(file);

        Session after = new Session();
        after.startEmpty();
        after.open(file);

        assertEquals(before.bpm(), after.bpm());
        assertEquals(before.bars(), after.bars());
        assertEquals(0.37f, after.masterGain());
        assertTrue(after.masterMuted());
        assertEquals(before.scale(), after.scale());
        assertEquals(before.chords(), after.chords());
        assertEquals(before.code(), after.code());
        assertEquals(3, after.tracks().selectedIndex());
        assertEquals(before.tracks().size(), after.tracks().size());
        for (int i = 1; i < before.tracks().size(); i++) {
            var original = (StudioTrack.Melody)before.tracks().get(i);
            var restored = (StudioTrack.Melody)after.tracks().get(i);
            assertEquals(original.source(), restored.source());
            assertEquals(original.name(), restored.name());
            assertEquals(original.gain(), restored.gain());
            assertEquals(original.muted(), restored.muted());
            assertEquals(original.soloed(), restored.soloed());
            assertEquals(original.instrument().setting(), restored.instrument().setting());
            assertNotSame(original.instrument().synth(), restored.instrument().synth());
        }
        var restored = ((StudioTrack.Melody)after.tracks().get(3)).instrument();
        assertTrue(restored.external());
        assertEquals(4, restored.channel());
        assertEquals(71, restored.controller());
        assertEquals(55, restored.filter());
        assertArrayEquals(before.rows().getFirst().accents(), after.rows().getFirst().accents());
        assertEquals(before.rows().getFirst().envelope(), after.rows().getFirst().envelope());
        assertEquals(before.rows().getFirst().channel().params(), after.rows().getFirst().channel().params());
        assertFalse(after.undo());
    }

    @Test
    void midiRangesAudioSlicesAndTheKitCanMoveWithTheProject() throws Exception {
        Path original = Files.createDirectory(directory.resolve("original"));
        Path midi = midi(original.resolve("bas i trąbki.mid"));
        Path audio = original.resolve("loop.wav");
        new AudioEngine(SampleBank.from(Map.of(Drum.KICK, Sample.mono(0.25f))))
                .writeWav(new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1))), 1, audio);
        Path kit = Files.createDirectory(original.resolve("kit"));
        Files.copy(audio, kit.resolve("kick.wav"));
        Session before = new Session(); before.startEmpty();
        var window = new MidiWindow(midi, 2, 3.75, 8.0, true);
        before.addMidiWindows(List.of(window));
        before.tracks().replace(1, before.tracks().get(1).named("Trąbki 🎺"));
        var loop = before.newLoop(audio);
        loop = loop.cutInto(StudioTrack.Slicing.of(loop.audio(), 1, false, 4, List.of(3, 1, -1, 0)))
                .lockedTo("EIGHTH");
        loop.channel().setParams(EffectParams.DRY.withCrush(0.4f).withDelayMix(0.3f));
        before.addTrack(loop);
        before.setDrumSamplesFolder(kit);
        before.save(original.resolve("jam.properties"));
        Properties properties = SessionFile.readProperties(original.resolve("jam.properties"));
        assertEquals("bas i trąbki.mid", properties.getProperty("track.1.window.file"));
        assertEquals("loop.wav", properties.getProperty("track.2.file"));
        Path moved = Files.createDirectory(directory.resolve("moved"));
        for (String name : List.of("jam.properties", "bas i trąbki.mid", "loop.wav"))
            Files.copy(original.resolve(name), moved.resolve(name));
        Files.createDirectory(moved.resolve("kit")); Files.copy(kit.resolve("kick.wav"), moved.resolve("kit/kick.wav"));

        Session after = new Session(); after.open(moved.resolve("jam.properties"));

        var restored = (StudioTrack.Melody)after.tracks().get(1);
        assertEquals("Trąbki 🎺", restored.name());
        assertEquals(window.withFile(moved.resolve("bas i trąbki.mid")), restored.window());
        assertEquals(before.notesOf(window, 32), after.notesOf(restored.source(), 32));
        assertTrue(after.notesOf(restored.source(), 32).stream()
                .allMatch(n -> ((Voice.Pitch)n.voice()).midiNote() == 67));
        var restoredLoop = (StudioTrack.Loop)after.tracks().get(2);
        assertEquals(loop.slicing(), restoredLoop.slicing());
        assertEquals(loop.channel().params(), restoredLoop.channel().params());
        assertEquals("EIGHTH", restoredLoop.delaySync());
        assertEquals(moved.resolve("loop.wav"), restoredLoop.file());
        assertEquals(moved.resolve("kit"), after.drumSamplesFolder());

        var previous = after.jam(); Files.delete(moved.resolve("bas i trąbki.mid"));
        assertThrows(Exception.class, () -> after.open(moved.resolve("jam.properties")));
        assertSame(previous, after.jam());
    }

    @Test
    void legacyJamPropertiesCanBeOpenedAndSavedAsAFullSession() throws Exception {
        Path midi = midi(directory.resolve("source.mid"));
        Path file = directory.resolve("legacy.properties");
        Files.writeString(file, "file=" + midi.toString().replace('\\', '/')
                + "\ntrack=2\nfromBar=1\nbars=2\nsynth=pluck\ndrums=disco\n", StandardCharsets.UTF_8);
        Session session = new Session(); session.open(file);
        assertEquals(2, session.tracks().size());
        assertEquals(2, ((StudioTrack.Melody)session.tracks().get(1)).window().trackIndex());
        session.save(file);
        assertEquals("1", SessionFile.readProperties(file).getProperty("session.version"));
        Session restored = new Session(); restored.open(file);
        assertEquals(session.tracks().size(), restored.tracks().size());
        assertEquals(session.bars(), restored.bars());
    }

    @Test
    void musicJamExamplesResolveProjectRelativeAssetsAndNamedGrooves() throws Exception {
        Path resources = Files.createDirectories(directory.resolve("musicjam/src/main/resources"));
        midi(resources.resolve("song_insomnia.mid"));
        Path config = resources.resolve("jam-insomnia.properties");
        Files.writeString(config, "file=src/main/resources/song_insomnia.mid\ntrack=0\nfromBar=0\nbars=8\nloops=2\nsynth=anthem\ndelay=0.2\ndrums=insomnia\n");
        Session session = new Session(); session.open(config);
        assertEquals(8, session.bars());
        assertEquals(5, session.rows().size());
        assertEquals(0.2f, ((StudioTrack.Melody)session.tracks().get(1)).instrument().setting().effects().delayMix());
        for (String pattern : List.of("shape", "worry", "dre", "giorgio", "insomnia"))
            assertTrue(Sounds.drumPatterns().containsKey(pattern));
    }

    @Test
    void anEmptyWorkspaceAndEditedNotesWithTheirMidiOriginCanBeSaved() throws Exception {
        Session session = new Session(); session.startEmpty();
        Path file = directory.resolve("empty.properties"); session.save(file);
        Session restored = new Session(); restored.open(file);
        assertEquals(1, restored.tracks().size());
        assertTrue(GridRow.notes(restored.rows(), 4, 32).isEmpty());
        Path source = midi(directory.resolve("source.mid"));
        session.addMidiWindows(List.of(new MidiWindow(source, 2, 0)));
        var window = ((StudioTrack.Melody)session.tracks().selected()).window();
        var own = new MelodySource.OwnNotes(List.of(new Note(0.5, new Voice.Pitch(70), 0.75, 0.6f)), window);
        session.changeSource(own);
        session.save(file);
        Files.delete(source);
        restored.open(file);
        assertEquals(own, ((StudioTrack.Melody)restored.tracks().selected()).source());
    }

    @Test
    void derivedSynthFieldsAreRecomputedFromTheKnobs() throws Exception {
        Session session = new Session(); DemoJam.startIn(session);
        Path file = directory.resolve("derived.properties"); session.save(file);
        String text = Files.readString(file).replaceAll("(?m)^track.1.synth.params.unisonGain=.*$", "track.1.synth.params.unisonGain=999")
                .replaceAll("(?m)^track.1.synth.params.resonanceCompensation=.*$", "track.1.synth.params.resonanceCompensation=999");
        Files.writeString(file, text);
        Session restored = new Session(); restored.open(file);
        assertEquals(((StudioTrack.Melody)session.tracks().get(1)).instrument().setting(),
                ((StudioTrack.Melody)restored.tracks().get(1)).instrument().setting());
    }

    private Path midi(Path file) throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480); sequence.createTrack();
        for (int pitch : new int[]{48, 67}) {
            var track = sequence.createTrack();
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, 100), 1920));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0), 2400));
        }
        MidiSystem.write(sequence, 1, file.toFile()); return file;
    }

    @Test
    void anInvalidProjectLeavesTheCurrentWorkspaceAndHistoryIntact() throws Exception {
        Session session = new Session();
        DemoJam.startIn(session);
        session.cycleAccent(0, 1);
        var jam = session.jam();
        var tracks = session.tracks();
        Path file = directory.resolve("bad.properties");
        Files.writeString(file, "session.version=999\ntracks.count=0\n");
        assertThrows(Exception.class, () -> session.open(file));
        assertSame(tracks, session.tracks());
        assertSame(jam, session.jam());
        assertTrue(session.undo());
    }
}
