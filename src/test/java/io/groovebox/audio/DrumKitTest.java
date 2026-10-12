package io.groovebox.audio;

import io.groovebox.model.Drum;
import io.groovebox.model.Envelope;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Song;
import io.groovebox.model.DrumTrack;
import io.groovebox.session.Playback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DrumKitTest {
    @Test
    void aKitUnderstandsCommonNamesAndSynthesizesMissingDrums(@TempDir Path directory) throws Exception {
        try (var wav = WavFileOutput.open(directory.resolve("Kick.WAV"), 1000, 1)) {
            wav.write(new byte[] {0, 64, 0, 64}, 4);
        }
        var kit = SampleBank.loadKit(directory, 1000);
        assertEquals(0.5f, kit.sample(Drum.KICK).copyMono()[0], 0.001f);
        assertEquals(directory.resolve("Kick.WAV"), kit.sourceFiles().get(Drum.KICK));
        assertEquals(1, kit.sourceFiles().size());
        for (Drum drum : Drum.values()) assertNotNull(kit.sample(drum));
    }

    @Test
    void aFailedLoadLeavesThePreviousKitAndSongIntact(@TempDir Path directory) throws Exception {
        var engine = new AudioEngine(SampleBank.from(Map.of(Drum.KICK, Sample.mono(0.25f))));
        var song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1)));
        var jam = new AudioEngine.Jam(song, (pitch, frames, rate) -> Sample.mono(0));
        try (var wav = WavFileOutput.open(directory.resolve("bd.wav"), 44100, 1)) {
            wav.write(new byte[] {0, 64, 0, 64}, 4);
        }
        Playback playback = new Playback(engine, () -> jam);
        playback.loadDrumKit(directory);
        assertEquals(0.5f, engine.render(song, 1).sampleAt(0, 0), 0.001f);
        Files.writeString(directory.resolve("sd.wav"), "broken audio");
        assertThrows(Exception.class, () -> playback.loadDrumKit(directory));
        assertEquals(0.5f, engine.render(song, 1).sampleAt(0, 0), 0.001f);
        assertFalse(playback.playing());
        playback.close();
    }

    @Test
    void anUnrecognizedOrBrokenKitDoesNotSilentlyReplaceTheSounds(@TempDir Path directory) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SampleBank.loadKit(directory, 1000));
        Files.writeString(directory.resolve("bd.wav"), "broken audio");
        assertThrows(Exception.class, () -> SampleBank.loadKit(directory, 1000));
    }

    @Test
    void changingAKitRefreshesUpcomingHitsIncludingCachedEnvelopeShapes() {
        for (Envelope envelope : List.of(Envelope.NONE, new Envelope(0.1, 0))) {
            var engine = new AudioEngine(SampleBank.from(Map.of(Drum.KICK, Sample.mono(0.25f))), 1000, 128, 8);
            var song = new Song(120, 4, List.of(new MelodyTrack(List.of(
                    new Note(0, Drum.KICK, 0.1, 1, envelope),
                    new Note(1, Drum.KICK, 0.1, 1, envelope)), 4, 1)));
            var renderer = engine.liveRenderer(() -> new AudioEngine.Jam(song, (pitch, frames, rate) -> {
                throw new AssertionError("No synth notes");
            }), false);
            float[] mix = new float[256];
            renderer.renderNext(mix);
            assertEquals(0.25f, mix[0]);
            engine.setSampleBank(SampleBank.from(Map.of(Drum.KICK, Sample.mono(0.75f))));
            renderer.takeNow();
            for (int block = 0; block < 3; block++) renderer.renderNext(mix);
            assertEquals(0.75f, mix[(500 - 384) * 2]);
        }
    }
}
