package io.groovebox.audio;

/** An immutable block's actual peak levels: tracks before their effects, and the finished stereo output. */
public final class MixLevels {
    public static final MixLevels SILENT = new MixLevels(new float[0], 0);
    private final float[] tracks;
    private final float master;

    MixLevels(float[] tracks, float master) { this.tracks = tracks.clone(); this.master = master; }
    public float track(int index) { return index >= 0 && index < tracks.length ? tracks[index] : 0; }
    public float master() { return master; }
}
