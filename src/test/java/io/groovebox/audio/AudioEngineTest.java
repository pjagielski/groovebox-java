package io.groovebox.audio;

import io.groovebox.model.Drum;
import io.groovebox.model.Song;
import io.groovebox.model.DrumTrack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioEngineTest {

    @Test
    void rendersHitAtItsExactAbsoluteFrame() {
        var samples = SampleBank.from(Map.of(
                Drum.KICK, Sample.mono(1.0f)
        ));
        var engine = new AudioEngine(samples, 1_000, 128, 8);
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, ".X..", 0.5f)
        ));

        var audio = engine.render(song, 1);

        assertEquals(0.0f, audio.sampleAt(499, 0));
        assertEquals(0.5f, audio.sampleAt(500, 0));
        assertEquals(0.5f, audio.sampleAt(500, 1));
        assertEquals(0.0f, audio.sampleAt(501, 0));
    }

    @Test
    void streamsAnExactNumberOfFramesToWav(@TempDir Path directory) throws Exception {
        var samples = SampleBank.from(Map.of(
                Drum.KICK, Sample.mono(1.0f)
        ));
        var engine = new AudioEngine(samples, 1_000, 128, 8);
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 0.5f)
        ));
        Path wav = directory.resolve("one-bar.wav");

        engine.writeWav(song, 1, wav);

        try (var audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            assertEquals(2_000, audio.getFrameLength());
            assertEquals(1_000, audio.getFormat().getSampleRate());
            assertEquals(2, audio.getFormat().getChannels());
        }
    }

    @Test
    void stolenVoicePlaysUntilTheExactFrameOfItsReplacement() {
        float[] sustained = new float[1_000];
        java.util.Arrays.fill(sustained, 1.0f);
        var samples = SampleBank.from(Map.of(
                Drum.KICK, Sample.mono(sustained),
                Drum.SNARE, Sample.mono(sustained)
        ));
        var engine = new AudioEngine(samples, 1_000, 128, 1);
        var song = new Song(600, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 0.25f),
                new DrumTrack(Drum.SNARE, ".X..", 0.75f)
        ));

        var audio = engine.render(song, 1);

        assertEquals(0.25f, audio.sampleAt(99, 0));
        assertEquals(0.75f, audio.sampleAt(100, 0));
    }
}
