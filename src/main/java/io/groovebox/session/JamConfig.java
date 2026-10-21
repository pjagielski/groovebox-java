package io.groovebox.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * What a jam config says: which MIDI file and track the melody comes from, and which bars of it,
 * which synth plays it, which drum pattern goes under it, and an optional external MIDI device to
 * send the melody to instead. And where the jam's loops are kept ({@code loopsFolder}), for a
 * chooser of loops to open in; null when it does not say.
 *
 * <p>Two ways to build one: fluent ({@link #forFile}), or from YAML or a legacy properties file
 * ({@link #fromFile}).
 */
public record JamConfig(
        Path file, int trackIndex, int startBar, int bars, String synth, String drums,
        String midiDevice, int midiLatencyMillis, int midiChannel, double delayMix, Path loopsFolder) {

    /**
     * How long an external synth takes to sound a note, in milliseconds: measured for Surge XT on
     * the default Windows device. It depends on the synth and its buffer size, so measure your own
     * and set {@code midiLatency}.
     */
    public static final int DEFAULT_MIDI_LATENCY_MILLIS = 50;

    public JamConfig {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(synth, "synth");
        Objects.requireNonNull(drums, "drums");
        if (midiChannel < 1 || midiChannel > 16) {
            throw new IllegalArgumentException(
                    "midiChannel counts from 1 to 16, the way a synth does, not " + midiChannel);
        }
        if (midiLatencyMillis < 0) {
            throw new IllegalArgumentException("midiLatency must not be negative");
        }
        if (bars <= 0) {
            throw new IllegalArgumentException("bars must be positive");
        }
    }

    public static Builder forFile(String file) {
        return forFile(Path.of(file));
    }

    public static Builder forFile(Path file) {
        return new Builder(file);
    }

    public static JamConfig fromPropertiesFile(Path path) throws IOException {
        return fromFile(path);
    }

    /** Reads a hand-edited YAML config or an older MusicJam properties file. */
    public static JamConfig fromFile(Path path) throws IOException {
        Properties properties = SessionFile.readDocument(path);
        String file = properties.getProperty("file");
        if (file == null) {
            throw new IllegalArgumentException("Config file " + path + " is missing \"file\"");
        }
        Builder builder = forFile(file);
        applyIfPresent(properties, "track", builder::track);
        applyIfPresent(properties, "fromBar", builder::fromBar);
        applyIfPresent(properties, "bars", builder::bars);
        String synth = properties.getProperty("synth");
        if (synth != null) {
            builder.synth(synth);
        }
        String drums = properties.getProperty("drums");
        if (drums != null) {
            builder.drums(drums);
        }
        String midiDevice = properties.getProperty("midiDevice");
        if (midiDevice != null) {
            builder.midiDevice(midiDevice);
        }
        String delay = properties.getProperty("delay");
        if (delay != null) {
            builder.delayMix(Double.parseDouble(delay.trim()));
        }
        applyIfPresent(properties, "midiLatency", builder::midiLatency);
        String loopsFolder = properties.getProperty("loopsFolder");
        if (loopsFolder != null && !loopsFolder.isBlank()) {
            // a folder beside the jam can be named as it is from there
            Path beside = path.toAbsolutePath().getParent();
            builder.loopsFolder(beside == null ? Path.of(loopsFolder.trim()) : beside.resolve(loopsFolder.trim()));
        }
        applyIfPresent(properties, "midiChannel", builder::midiChannel);
        return builder.build();
    }

    private static void applyIfPresent(Properties properties, String key, java.util.function.IntConsumer setter) {
        String value = properties.getProperty(key);
        if (value != null) {
            setter.accept(Integer.parseInt(value.trim()));
        }
    }

    /** {@code midiChannel} the way javax.sound.midi counts channels: from 0. */
    public int midiChannelIndex() {
        return midiChannel - 1;
    }

    public static final class Builder {
        private final Path file;
        private int trackIndex = 1;
        private int startBar = 0;
        private int bars = 2;
        private String synth = "anthem";
        private String drums = "disco";
        private String midiDevice;
        private int midiLatencyMillis = DEFAULT_MIDI_LATENCY_MILLIS;
        // how much of the delay is heard from the start; a jam can ask for a little without a hand
        private double delayMix;
        private int midiChannel = 1;
        private Path loopsFolder;

        private Builder(Path file) {
            this.file = file;
        }

        public Builder track(int trackIndex) {
            this.trackIndex = trackIndex;
            return this;
        }

        public Builder fromBar(int startBar) {
            this.startBar = startBar;
            return this;
        }

        public Builder bars(int bars) {
            this.bars = bars;
            return this;
        }

        public Builder synth(String synth) {
            this.synth = synth;
            return this;
        }

        public Builder drums(String drums) {
            this.drums = drums;
            return this;
        }

        public Builder midiDevice(String midiDevice) {
            this.midiDevice = midiDevice;
            return this;
        }

        /** How loud the delay starts, 0 to 1. A jam that wants a little of it can say so. */
        public Builder delayMix(double mix) {
            this.delayMix = Math.max(0.0, Math.min(1.0, mix));
            return this;
        }

        public Builder midiLatency(int millis) {
            this.midiLatencyMillis = millis;
            return this;
        }

        /**
         * The channel an external synth listens on, counted from 1 to 16 the way a synth counts -
         * javax.sound.midi counts from 0, see {@link JamConfig#midiChannelIndex}.
         */
        public Builder midiChannel(int channel) {
            this.midiChannel = channel;
            return this;
        }

        /** Where the jam's loops are kept, for a chooser of loops to open in. */
        public Builder loopsFolder(Path folder) {
            this.loopsFolder = folder;
            return this;
        }

        public JamConfig build() {
            return new JamConfig(file, trackIndex, startBar, bars, synth, drums, midiDevice,
                    midiLatencyMillis, midiChannel, delayMix, loopsFolder);
        }
    }
}
