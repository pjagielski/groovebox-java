package io.groovebox.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YamlSessionTest {
    @TempDir Path directory;

    @Test
    void aYamlSessionUsesNestedSettingsAndListsAndRestoresTheWholeJam() throws Exception {
        Session before = new Session(); DemoJam.startIn(before);
        before.tracks().replace(1, before.tracks().get(1).named("Trąbki: #1 🎺"));
        before.setMasterGain(0.37f); before.setMuted(2, true); before.setSoloed(3, true);
        Path file = directory.resolve("jam.yml"); before.save(file);
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("tracks:"));
        assertFalse(yaml.contains("track.0."));
        assertFalse(yaml.contains("tracks.count"));
        assertFalse(yaml.contains("#groovebox"));
        before.save(file);
        assertEquals(yaml, Files.readString(file));
        Session after = new Session(); after.open(file);
        assertEquals(before.bpm(), after.bpm()); assertEquals(before.bars(), after.bars());
        assertEquals(before.masterGain(), after.masterGain());
        assertEquals(before.rows().size(), after.rows().size());
        for (int i = 0; i < before.tracks().size(); i++) {
            var original = before.tracks().get(i); var restored = after.tracks().get(i);
            assertEquals(original.name(), restored.name()); assertEquals(original.gain(), restored.gain());
            assertEquals(original.muted(), restored.muted()); assertEquals(original.soloed(), restored.soloed());
            if (original instanceof StudioTrack.Melody melody) {
                var saved = (StudioTrack.Melody) restored;
                assertEquals(melody.source(), saved.source());
                assertEquals(melody.instrument().setting(), saved.instrument().setting());
            }
        }
    }

    @Test
    void aSimpleYamlConfigHasTheSameMeaningAsItsPropertiesPredecessor() throws Exception {
        Path file = directory.resolve("jam.yaml");
        Files.writeString(file, """
                # A hand-edited jam
                file: 'bas i trąbki.mid'
                track: 2
                fromBar: 3
                bars: 8
                synth: sub
                drums: insomnia
                delay: 0.2
                loopsFolder: loops
                """);
        JamConfig config = JamConfig.fromFile(file);
        assertEquals(Path.of("bas i trąbki.mid"), config.file());
        assertEquals(2, config.trackIndex()); assertEquals(3, config.startBar());
        assertEquals(8, config.bars()); assertEquals("sub", config.synth());
        assertEquals("insomnia", config.drums()); assertEquals(0.2, config.delayMix());
        assertEquals(directory.resolve("loops"), config.loopsFolder());
    }

    @Test
    void malformedYamlLeavesTheActiveSessionIntact() throws Exception {
        Session session = new Session(); DemoJam.startIn(session);
        var previous = session.jam();
        Path file = directory.resolve("bad.yml"); Files.writeString(file, "session: [unfinished");
        assertThrows(Exception.class, () -> session.open(file));
        assertSame(previous, session.jam());
    }

    @Test
    void duplicateKeysAndMultipleDocumentsAreRejectedWithoutReplacingTheJam() throws Exception {
        Session session = new Session(); DemoJam.startIn(session);
        var previous = session.jam();
        for (String content : java.util.List.of("file: a.mid\nfile: b.mid\n",
                "file: a.mid\n---\nfile: b.mid\n", "session: {version: 999}\n",
                "tracks: {items: [null]}\n", "value: &cycle {again: *cycle}\n")) {
            Path file = directory.resolve("bad.yml"); Files.writeString(file, content);
            assertThrows(Exception.class, () -> session.open(file));
            assertSame(previous, session.jam());
        }
    }

    @Test
    void stringNamesAreNotReinterpretedAsYamlBooleansOrNumbers() throws Exception {
        Session session = new Session(); session.startEmpty();
        for (String name : java.util.List.of("true", "null", "123", "OFF", "ślad: #1")) {
            session.tracks().replace(0, session.tracks().get(0).named(name));
            Path file = directory.resolve("names.yaml"); session.save(file);
            Session restored = new Session(); restored.open(file);
            assertEquals(name, restored.tracks().get(0).name());
        }
    }

    @Test
    void anExistingPropertiesSessionCanBeConvertedToYaml() throws Exception {
        Session before = new Session(); DemoJam.startIn(before);
        Path properties = directory.resolve("old.properties"); before.save(properties);
        String original = Files.readString(properties);
        Session converted = new Session(); converted.open(properties);
        Path yaml = directory.resolve("converted.yml"); converted.save(yaml);
        Session restored = new Session(); restored.open(yaml);
        assertEquals(converted.tracks().size(), restored.tracks().size());
        assertEquals(converted.rows().get(0).gain(), restored.rows().get(0).gain());
        assertEquals(original, Files.readString(properties));
    }

    @Test
    void anUncutAudioLoopRoundTripsWithoutYamlAliases() throws Exception {
        Path audio = directory.resolve("loop.wav");
        var format = new javax.sound.sampled.AudioFormat(44100, 16, 1, true, false);
        try (var input = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(new byte[100]), format, 50)) {
            javax.sound.sampled.AudioSystem.write(input, javax.sound.sampled.AudioFileFormat.Type.WAVE, audio.toFile());
        }
        Session before = new Session(); before.startEmpty(); before.addTrack(before.newLoop(audio));
        Path yaml = directory.resolve("loop.yml"); before.save(yaml);
        Session after = new Session(); after.open(yaml);
        assertEquals(StudioTrack.Slicing.NONE, ((StudioTrack.Loop) after.tracks().get(1)).slicing());
    }
}
