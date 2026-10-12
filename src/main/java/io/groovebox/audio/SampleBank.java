package io.groovebox.audio;

import io.groovebox.model.Drum;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Objects;

/** A complete drum kit: recognized WAV files, with synthesized sounds filling any missing drums. */
public final class SampleBank {
    /** Loads a user-selected kit, requiring at least one recognized drum file. */
    public static SampleBank loadKit(Path directory, int sampleRate)
            throws IOException, UnsupportedAudioFileException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Choose a directory containing drum WAV files");
        }
        SampleBank kit = load(directory, sampleRate);
        if (kit.files.isEmpty()) {
            throw new IllegalArgumentException("No recognized drum WAV files. Use kick.wav (bd.wav), snare.wav (sd.wav), "
                    + "closed-hat.wav (hh.wav), open-hat.wav (oh.wav), or clap.wav (cp.wav).");
        }
        return kit;
    }

    private final EnumMap<Drum, Sample> samples;
    private final Map<Drum, Path> files;

    private SampleBank(Map<Drum, Sample> samples) {
        this(samples, Map.of());
    }

    private SampleBank(Map<Drum, Sample> samples, Map<Drum, Path> files) {
        this.samples = new EnumMap<>(Drum.class);
        this.samples.putAll(samples);
        this.files = Map.copyOf(files);
    }

    public static SampleBank from(Map<Drum, Sample> samples) {
        Objects.requireNonNull(samples, "samples");
        return new SampleBank(samples);
    }

    public static SampleBank load(Path directory, int sampleRate)
            throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(directory, "directory");
        var loaded = new EnumMap<Drum, Sample>(Drum.class);
        var sources = new EnumMap<Drum, Path>(Drum.class);
        Map<String, Path> names = new HashMap<>();
        if (Files.isDirectory(directory)) {
            try (var entries = Files.list(directory)) {
                entries.filter(Files::isRegularFile).sorted().forEach(path ->
                        names.putIfAbsent(path.getFileName().toString().toLowerCase(Locale.ROOT), path));
            }
        }
        for (Drum drum : Drum.values()) {
            Path path = names.get(fileName(drum));
            if (path == null) {
                path = aliases(drum).stream().map(names::get).filter(Objects::nonNull).findFirst().orElse(null);
            }
            Sample sample = path != null ? WavSampleLoader.load(path, sampleRate) : DrumSamples.create(drum, sampleRate);
            if (path != null) sources.put(drum, path);
            loaded.put(drum, sample);
        }
        return new SampleBank(loaded, sources);
    }

    /** The files used by this kit; drums absent from this map are synthesized. */
    public Map<Drum, Path> sourceFiles() {
        return files;
    }

    private static List<String> aliases(Drum drum) {
        return switch (drum) {
            case KICK -> List.of("kick.wav");
            case SNARE -> List.of("snare.wav");
            case CLOSED_HAT -> List.of("closed-hat.wav", "closed_hat.wav", "hihat.wav");
            case OPEN_HAT -> List.of("open-hat.wav", "open_hat.wav");
            case CLAP -> List.of("clap.wav");
        };
    }

    /** Default WAV file for a drum; the music model does not depend on sample files. */
    public static String fileName(Drum drum) {
        return switch (drum) {
            case KICK -> "bd.wav";
            case SNARE -> "sd.wav";
            case CLOSED_HAT -> "hh.wav";
            case OPEN_HAT -> "oh.wav";
            case CLAP -> "cp.wav";
        };
    }

    public Sample sample(Drum drum) {
        Sample sample = samples.get(drum);
        if (sample == null) {
            throw new IllegalArgumentException("No sample configured for " + drum);
        }
        return sample;
    }
}
