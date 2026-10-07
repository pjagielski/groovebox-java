package io.groovebox.session;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JamDelayTest {

    @Test
    void aJamCanAskForItsDelayToBeHeardFromTheStart() throws Exception {
        Path config = Files.createTempFile("jam", ".properties");
        Files.writeString(config, """
                file=song.mid
                synth=anthem
                delay=0.2
                """);

        JamConfig request = JamConfig.fromPropertiesFile(config);

        assertEquals("anthem", request.synth());
        assertEquals(0.2, request.delayMix(), 1e-9);
        Files.delete(config);
    }

    @Test
    void aJamThatSaysNothingAboutItGetsNone() throws Exception {
        Path config = Files.createTempFile("jam", ".properties");
        Files.writeString(config, "file=song.mid\n");

        assertEquals(0.0, JamConfig.fromPropertiesFile(config).delayMix());
        Files.delete(config);
    }
}
