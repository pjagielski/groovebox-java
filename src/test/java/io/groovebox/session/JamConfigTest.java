package io.groovebox.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JamConfigTest {

    @Test
    void builderAppliesDefaults() {
        JamConfig request = JamConfig.forFile("song.mid").build();

        assertEquals(Path.of("song.mid"), request.file());
        assertEquals(1, request.trackIndex());
        assertEquals(0, request.startBar());
        assertEquals(2, request.bars());
        assertEquals("anthem", request.synth());
        assertEquals("disco", request.drums());
        assertNull(request.midiDevice());
        assertEquals(50, request.midiLatencyMillis());
        assertEquals(1, request.midiChannel());
    }

    @Test
    void builderOverridesEveryField() {
        JamConfig request = JamConfig.forFile("song.mid")
                .track(3)
                .fromBar(36)
                .bars(4)
                .synth("pad")
                .midiDevice("loopMIDI")
                .midiLatency(12)
                .build();

        assertEquals(3, request.trackIndex());
        assertEquals(36, request.startBar());
        assertEquals(4, request.bars());
        assertEquals("pad", request.synth());
        assertEquals("loopMIDI", request.midiDevice());
        assertEquals(12, request.midiLatencyMillis());
    }

    @Test
    void rejectsNonPositiveBars() {
        assertThrows(IllegalArgumentException.class,
                () -> JamConfig.forFile("song.mid").bars(0).build());
    }

    @Test
    void rejectsNegativeMidiLatency() {
        assertThrows(IllegalArgumentException.class,
                () -> JamConfig.forFile("song.mid").midiLatency(-1).build());
    }

    @Test
    void loadsFromPropertiesFile(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("jam.properties");
        Files.writeString(config, """
                file=song.mid
                track=3
                fromBar=36
                bars=4
                synth=pad
                midiDevice=loopMIDI
                midiLatency=12
                midiChannel=2
                """);

        JamConfig request = JamConfig.fromPropertiesFile(config);

        assertEquals(Path.of("song.mid"), request.file());
        assertEquals(3, request.trackIndex());
        assertEquals(36, request.startBar());
        assertEquals(4, request.bars());
        assertEquals("pad", request.synth());
        assertEquals("loopMIDI", request.midiDevice());
        assertEquals(12, request.midiLatencyMillis());
        assertEquals(2, request.midiChannel());
        assertEquals(1, request.midiChannelIndex());
    }

    @Test
    void propertiesFileOnlyNeedsFile(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("jam.properties");
        Files.writeString(config, "file=song.mid\n");

        JamConfig request = JamConfig.fromPropertiesFile(config);

        assertEquals(Path.of("song.mid"), request.file());
        assertEquals(1, request.trackIndex());
        assertEquals(2, request.bars());
        assertEquals("anthem", request.synth());
        assertNull(request.midiDevice());
        assertEquals(50, request.midiLatencyMillis());
        assertEquals(1, request.midiChannel());
    }

    @Test
    void aJamsLoopsFolderIsFoundFromWhereTheJamIs(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("jam.properties");
        Files.writeString(config, "file=song.mid\nloopsFolder=loops\n");
        Path elsewhere = directory.resolve("other.properties");
        Files.writeString(elsewhere, "file=song.mid\nloopsFolder=" + directory.resolve("far").toString()
                .replace('\\', '/') + "\n");

        assertEquals(directory.toAbsolutePath().resolve("loops"), JamConfig.fromPropertiesFile(config).loopsFolder());
        assertEquals(directory.resolve("far"), JamConfig.fromPropertiesFile(elsewhere).loopsFolder());
        Files.writeString(config, "file=song.mid\n");
        assertNull(JamConfig.fromPropertiesFile(config).loopsFolder(), "a jam that does not say");
    }

    @Test
    void propertiesFileRequiresFile(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("jam.properties");
        Files.writeString(config, "track=3\n");

        assertThrows(IllegalArgumentException.class, () -> JamConfig.fromPropertiesFile(config));
    }
}
