package io.groovebox.audio;

import io.groovebox.model.Song;

import java.util.Arrays;

/**
 * Each track's gain as the jam has it now, taken up within a block rather than at the next loop: a
 * mute or a fader is heard at once, on the notes already sounding too. A change glides across one
 * block, a few milliseconds, so a note cut short by a mute does not click.
 *
 * <p>Tracks are known by their place in the song, so a track that moves while its notes are queued
 * takes the gain of the one that moved into its place until the next loop.
 */
final class TrackGains {
    private final int blockSize;
    private float[] from = new float[0];
    // read by the thread sending notes to an external synth, so replaced rather than written into
    private volatile float[] to = new float[0];

    TrackGains(int blockSize) {
        this.blockSize = blockSize;
    }

    /** The gains to reach by the end of the next block: the song's, from where the last block left off. */
    void next(Song song) {
        float[] previous = to;
        float[] wanted = new float[song.tracks().size()];
        for (int track = 0; track < wanted.length; track++) {
            wanted[track] = song.tracks().get(track).gain();
        }
        float[] start = Arrays.copyOf(previous, wanted.length);
        // a track new to the jam starts where it is meant to be, not faded in from nothing
        for (int track = previous.length; track < wanted.length; track++) {
            start[track] = wanted[track];
        }
        from = start;
        to = wanted;
    }

    /** No change asked for: the next block holds every gain where the last one ended. */
    void hold() {
        from = to;
    }

    /** A track's gain {@code offset} frames into the block; 1 for a voice that belongs to no track. */
    float at(int track, int offset) {
        float[] target = to;
        if (track < 0 || track >= target.length || track >= from.length) {
            return 1.0f;
        }
        return from[track] + (target[track] - from[track]) * offset / blockSize;
    }

    /** A track's gain as the jam has it now. Safe from any thread. */
    float now(int track) {
        float[] target = to;
        return track < 0 || track >= target.length ? 1.0f : target[track];
    }
}
