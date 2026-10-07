package io.groovebox.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WavSampleLoaderTest {

    @Test
    void aStereoWavKeepsItsTwoChannels(@TempDir Path directory) throws Exception {
        byte[] stereoPcm = {
                (byte) 0xff, 0x7f, 0x00, (byte) 0x80,
                0x00, 0x40, 0x00, 0x40
        };
        var format = new AudioFormat(44_100, 16, 2, true, false);
        Path wav = directory.resolve("sample.wav");
        try (var audio = new AudioInputStream(
                new ByteArrayInputStream(stereoPcm), format, 2)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, wav.toFile());
        }

        Sample sample = WavSampleLoader.load(wav, 44_100);

        assertEquals(2, sample.frameCount());
        assertTrue(sample.stereo(), "the two channels differ and are kept apart");
        assertEquals(1.0f, sample.copyStereo()[0][0], 0.0001f, "hard left in one ear");
        assertEquals(-1.0f, sample.copyStereo()[1][0], 0.0001f, "and the other way in the other");
        // what is heard in the middle is what a mono caller gets: the two averaged
        assertEquals(0.0f, sample.copyMono()[0], 0.0001f);
        assertEquals(0.5f, sample.copyMono()[1], 0.0001f);
    }

    @Test
    void convertsCommon48KhzSamplesToTheEngineRate(@TempDir Path directory) throws Exception {
        int sourceFrames = 480;
        byte[] stereoPcm = new byte[sourceFrames * 4];
        stereoPcm[0] = (byte) 0xff;
        stereoPcm[1] = 0x7f;
        stereoPcm[2] = (byte) 0xff;
        stereoPcm[3] = 0x7f;
        var format = new AudioFormat(48_000, 16, 2, true, false);
        Path wav = directory.resolve("sample-48k.wav");
        try (var audio = new AudioInputStream(
                new ByteArrayInputStream(stereoPcm), format, sourceFrames)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, wav.toFile());
        }

        Sample sample = WavSampleLoader.load(wav, 44_100);

        assertEquals(441, sample.frameCount());
        int firstTransient = -1;
        for (int frame = 0; frame < sample.frameCount(); frame++) {
            if (Math.abs(sample.valueAt(frame)) > 0.5f) {
                firstTransient = frame;
                break;
            }
        }
        assertEquals(0, firstTransient, "Resampling must not delay the initial transient");
    }
}
