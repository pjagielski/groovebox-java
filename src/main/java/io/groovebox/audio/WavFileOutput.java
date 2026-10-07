package io.groovebox.audio;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class WavFileOutput implements Closeable {
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int BYTES_PER_FRAME = CHANNELS * BITS_PER_SAMPLE / 8;

    private final OutputStream output;

    private WavFileOutput(OutputStream output) {
        this.output = output;
    }

    static WavFileOutput open(Path destination, int sampleRate, long frames) throws IOException {
        long dataBytes = Math.multiplyExact(frames, BYTES_PER_FRAME);
        if (dataBytes > 0xffff_ffffL - 36L) {
            throw new IllegalArgumentException("Classic WAV supports at most 4 GiB of audio data");
        }
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        var wav = new WavFileOutput(new BufferedOutputStream(Files.newOutputStream(destination)));
        wav.writeHeader(sampleRate, dataBytes);
        return wav;
    }

    void write(byte[] pcm, int length) throws IOException {
        output.write(pcm, 0, length);
    }

    private void writeHeader(int sampleRate, long dataBytes) throws IOException {
        ascii("RIFF");
        littleEndian32(36L + dataBytes);
        ascii("WAVE");
        ascii("fmt ");
        littleEndian32(16);
        littleEndian16(1);
        littleEndian16(CHANNELS);
        littleEndian32(sampleRate);
        littleEndian32((long) sampleRate * BYTES_PER_FRAME);
        littleEndian16(BYTES_PER_FRAME);
        littleEndian16(BITS_PER_SAMPLE);
        ascii("data");
        littleEndian32(dataBytes);
    }

    private void ascii(String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void littleEndian16(long value) throws IOException {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
    }

    private void littleEndian32(long value) throws IOException {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
    }

    @Override
    public void close() throws IOException {
        output.close();
    }
}
