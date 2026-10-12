package io.groovebox.session;

import io.groovebox.audio.AudioEngine;
import io.groovebox.audio.Sample;
import io.groovebox.audio.WavSampleLoader;
import io.groovebox.model.*;
import io.groovebox.synth.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.io.IOException;

/** A versioned properties document for the entire workspace, with asset paths relative to its file. */
final class SessionFile {
    record State(double bpm, double bars, Scale scale, String chords, String code,
                 List<StudioTrack> tracks, int selected, List<GridRow> rows, Path drumFolder,
                 float masterGain, boolean masterMuted) {}

    private final Properties properties;
    private final Path base;

    private SessionFile(Properties properties, Path file) {
        this.properties = properties;
        base = file.toAbsolutePath().normalize().getParent();
    }

    static Properties readProperties(Path file) throws IOException {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        return p;
    }

    static void write(Session session, Path file) throws Exception {
        SessionFile document = new SessionFile(new Properties(), file);
        document.put("session.version", 1);
        document.put("bpm", session.bpm());
        document.put("bars", session.bars());
        document.put("master.gain", session.masterGain());
        document.put("master.muted", session.masterMuted());
        document.put("key", session.scale().root());
        document.put("scale", session.scale().mode());
        document.put("chords", session.chords());
        document.put("code", session.code());
        if (session.drumSamplesFolder() != null) document.putPath("drumSamplesFolder", session.drumSamplesFolder());
        document.put("tracks.count", session.tracks().size());
        document.put("tracks.selected", session.tracks().selectedIndex());
        for (int i = 0; i < session.tracks().size(); i++) document.writeTrack("track." + i + ".", session.tracks().get(i));
        document.put("rows.count", session.rows().size());
        for (int i = 0; i < session.rows().size(); i++) {
            GridRow row = session.rows().get(i);
            String k = "row." + i + ".";
            document.put(k + "label", row.label());
            document.put(k + "drum", row.drum());
            document.put(k + "gain", row.gain());
            document.put(k + "noteBars", row.noteBars());
            document.put(k + "accentDepth", row.accentDepth());
            document.put(k + "steps", row.accents().length);
            for (int step = 0; step < row.accents().length; step++) document.put(k + "step." + step, row.accents()[step]);
            document.writeRecord(k + "envelope.", row.envelope());
            document.writeRecord(k + "effects.", row.channel().params());
        }
        Path destination = file.toAbsolutePath().normalize();
        // Keep the previous save intact if writing fails or the process stops before the move.
        Path temporary = Files.createTempFile(destination.getParent(), ".groovebox-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                document.properties.store(writer, "groovebox session - version 1; paths relative to this file");
            }
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static State read(Path file, Properties properties) throws Exception {
        SessionFile d = new SessionFile(properties, file);
        if (d.integer("session.version") != 1) throw new IllegalArgumentException("Unsupported session version");
        double bpm = d.number("bpm"), bars = d.number("bars");
        if (bpm <= 0 || bars < 0.25 || bars > 1024) throw new IllegalArgumentException("Invalid session tempo or length");
        int root = d.integer("key");
        if (root < 0 || root > 11) throw new IllegalArgumentException("Invalid session key");
        List<StudioTrack> tracks = new ArrayList<>();
        int count = d.count("tracks.count", 256);
        for (int i = 0; i < count; i++) tracks.add(d.readTrack("track." + i + "."));
        if (tracks.stream().filter(t -> t instanceof StudioTrack.Drums).count() != 1) {
            throw new IllegalArgumentException("A session needs exactly one drum track");
        }
        int selected = d.integer("tracks.selected");
        if (selected < 0 || selected >= count) throw new IllegalArgumentException("Invalid selected track");
        List<GridRow> rows = new ArrayList<>();
        for (int i = 0, n = d.count("rows.count", 256); i < n; i++) {
            String k = "row." + i + ".";
            float[] accents = new float[d.count(k + "steps", 64)];
            if (accents.length == 0) throw new IllegalArgumentException("A drum row needs steps");
            for (int step = 0; step < accents.length; step++) accents[step] = d.unit(k + "step." + step);
            double noteBars = d.number(k + "noteBars");
            if (noteBars <= 0) throw new IllegalArgumentException("Invalid drum note length");
            rows.add(new GridRow(d.text(k + "label"), Drum.valueOf(d.text(k + "drum")), accents,
                    d.unit(k + "gain"), noteBars, d.readRecord(k + "envelope.", Envelope.class),
                    d.unit(k + "accentDepth"), new LiveEffects(d.readRecord(k + "effects.", EffectParams.class))));
        }
        return new State(bpm, bars, new Scale(root, Scale.Mode.valueOf(d.text("scale"))),
                d.text("chords"), d.text("code"), List.copyOf(tracks), selected, List.copyOf(rows),
                properties.containsKey("drumSamplesFolder") ? d.path("drumSamplesFolder") : null,
                properties.containsKey("master.gain") ? d.unit("master.gain") : 1,
                properties.containsKey("master.muted") && d.bool("master.muted"));
    }

    private void writeTrack(String k, StudioTrack track) throws Exception {
        put(k + "name", track.name()); put(k + "gain", track.gain());
        put(k + "muted", track.muted()); put(k + "soloed", track.soloed());
        switch (track) {
            case StudioTrack.Drums drums -> put(k + "kind", "drums");
            case StudioTrack.Melody melody -> {
                put(k + "kind", "melody");
                Instrument instrument = melody.instrument();
                put(k + "synth.present", instrument != null);
                if (instrument != null) {
                    put(k + "synth.patch", instrument.patch());
                    put(k + "midi.channel", instrument.channel());
                    put(k + "midi.controller", instrument.controller());
                    put(k + "midi.filter", instrument.filter());
                    put(k + "midi.external", instrument.external());
                    if (instrument.setting() != null) {
                        put(k + "synth.preset", instrument.setting().preset() == null ? "" : instrument.setting().preset());
                        put(k + "synth.delaySync", instrument.setting().delaySync());
                        writeRecord(k + "synth.params.", instrument.setting().params());
                        writeRecord(k + "synth.effects.", instrument.setting().effects());
                    }
                }
                put(k + "source", melody.source() instanceof MidiWindow ? "midi" : "notes");
                if (melody.window() != null) writeWindow(k + "window.", melody.window());
                if (melody.source() instanceof MelodySource.OwnNotes own) {
                    put(k + "notes.count", own.notes().size());
                    for (int i = 0; i < own.notes().size(); i++) {
                        Note note = own.notes().get(i); String n = k + "note." + i + ".";
                        put(n + "beat", note.beat()); put(n + "duration", note.durationBeats());
                        put(n + "velocity", note.velocity());
                        put(n + "voice", note.voice() instanceof Voice.Pitch pitch ? "pitch:" + pitch.midiNote() : "drum:" + note.voice());
                        writeRecord(n + "envelope.", note.envelope());
                    }
                }
            }
            case StudioTrack.Loop loop -> {
                put(k + "kind", "loop"); putPath(k + "file", loop.file()); put(k + "bars", loop.bars());
                put(k + "delaySync", loop.delaySync()); writeRecord(k + "effects.", loop.channel().params());
                put(k + "slice.beats", loop.slicing().beats()); put(k + "slice.toHits", loop.slicing().toHits());
                put(k + "slice.starts", join(loop.slicing().starts())); put(k + "slice.order", join(loop.slicing().order()));
            }
        }
    }

    private StudioTrack readTrack(String k) throws Exception {
        String name = text(k + "name"); float gain = unit(k + "gain");
        boolean muted = bool(k + "muted"), soloed = bool(k + "soloed");
        return switch (text(k + "kind")) {
            case "drums" -> new StudioTrack.Drums(name, gain, muted, soloed);
            case "melody" -> {
                Instrument instrument = null;
                if (bool(k + "synth.present")) {
                    instrument = Instrument.of(text(k + "synth.patch"));
                    if (properties.containsKey(k + "synth.params.attackSeconds")) {
                        SynthParams params = readRecord(k + "synth.params.", SynthParams.class);
                        // Derived gain and compensation are rebuilt rather than trusted from disk.
                        params = params.withResonance(params.resonance()).withVoices(params.unisonVoices());
                        String preset = text(k + "synth.preset");
                        DelaySync.valueOf(text(k + "synth.delaySync"));
                        instrument.set(new SynthSetting(preset.isEmpty() ? null : preset, params,
                                readRecord(k + "synth.effects.", EffectParams.class), text(k + "synth.delaySync")));
                    }
                    int channel = count(k + "midi.channel", 15), controller = count(k + "midi.controller", 127);
                    instrument.setMidi(channel, controller, count(k + "midi.filter", 127));
                    instrument.setExternal(bool(k + "midi.external"));
                }
                MidiWindow window = properties.containsKey(k + "window.file") ? readWindow(k + "window.") : null;
                MelodySource source;
                switch (text(k + "source")) {
                    case "midi" -> {
                        if (window == null) throw new IllegalArgumentException("Missing MIDI source");
                        source = window;
                    }
                    case "notes" -> {
                        List<Note> notes = new ArrayList<>();
                        for (int i = 0, count = count(k + "notes.count", 1000000); i < count; i++) {
                            String n = k + "note." + i + ".", voice = text(n + "voice");
                            Voice value;
                            if (voice.startsWith("pitch:")) value = new Voice.Pitch(Integer.parseInt(voice.substring(6)));
                            else if (voice.startsWith("drum:")) value = Drum.valueOf(voice.substring(5));
                            else throw new IllegalArgumentException("Invalid note voice");
                            notes.add(new Note(number(n + "beat"), value, number(n + "duration"), unit(n + "velocity"),
                                    readRecord(n + "envelope.", Envelope.class)));
                        }
                        source = new MelodySource.OwnNotes(notes, window);
                    }
                    default -> throw new IllegalArgumentException("Invalid melody source");
                }
                yield new StudioTrack.Melody(name, gain, muted, soloed, source, instrument);
            }
            case "loop" -> {
                Path file = path(k + "file"); Sample audio = WavSampleLoader.load(file, AudioEngine.DEFAULT_SAMPLE_RATE);
                List<Integer> starts = integers(k + "slice.starts"), order = integers(k + "slice.order");
                int last = -1;
                for (int start : starts) {
                    if (start <= last || start >= audio.frameCount()) throw new IllegalArgumentException("Invalid slice boundary");
                    last = start;
                }
                for (int slice : order) if (slice < -1 || slice >= starts.size()) throw new IllegalArgumentException("Invalid slice order");
                DelaySync.valueOf(text(k + "delaySync"));
                yield new StudioTrack.Loop(name, gain, muted, soloed, file, number(k + "bars"), audio,
                        new StudioTrack.Slicing(number(k + "slice.beats"), bool(k + "slice.toHits"), starts, order),
                        new LiveEffects(readRecord(k + "effects.", EffectParams.class)), text(k + "delaySync"));
            }
            default -> throw new IllegalArgumentException("Unknown track type: " + text(k + "kind"));
        };
    }

    private void writeWindow(String k, MidiWindow window) {
        putPath(k + "file", window.file()); put(k + "track", window.trackIndex());
        put(k + "startBeat", window.startBeat()); put(k + "endBeat", window.endBeat()); put(k + "looping", window.looping());
    }

    private MidiWindow readWindow(String k) {
        return new MidiWindow(path(k + "file"), integer(k + "track"), number(k + "startBeat"), number(k + "endBeat"), bool(k + "looping"));
    }

    // Parameter records contain only scalar values. Their component names are the properties' names.
    private void writeRecord(String prefix, Record record) throws Exception {
        for (RecordComponent field : record.getClass().getRecordComponents()) put(prefix + field.getName(), field.getAccessor().invoke(record));
    }

    private <T extends Record> T readRecord(String prefix, Class<T> type) throws Exception {
        RecordComponent[] fields = type.getRecordComponents();
        Class<?>[] types = new Class<?>[fields.length]; Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType(); String key = prefix + fields[i].getName();
            if (types[i] == float.class) values[i] = decimal(key);
            else if (types[i] == double.class) values[i] = number(key);
            else if (types[i] == int.class) values[i] = integer(key);
            else if (types[i].isEnum()) values[i] = Arrays.stream(types[i].getEnumConstants())
                    .filter(e -> ((Enum<?>)e).name().equals(text(key))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Invalid " + key));
            else throw new IllegalArgumentException("Unsupported parameter type: " + key);
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }

    private void put(String key, Object value) { properties.setProperty(key, value instanceof Enum<?> e ? e.name() : value.toString()); }
    private void putPath(String key, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try { put(key, base.relativize(absolute).toString().replace('\\', '/')); }
        catch (IllegalArgumentException differentDrive) { put(key, absolute.toString().replace('\\', '/')); }
    }
    private Path path(String key) { return base.resolve(text(key)).normalize(); }
    private String text(String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Session is missing " + key);
        return value;
    }
    private int integer(String key) { return Integer.parseInt(text(key)); }
    private int count(String key, int max) {
        int value = integer(key);
        if (value < 0 || value > max) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private double number(String key) {
        double value = Double.parseDouble(text(key));
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private float decimal(String key) {
        float value = (float)number(key);
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private float unit(String key) {
        float value = decimal(key);
        if (value < 0 || value > 1) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private boolean bool(String key) {
        String value = text(key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid " + key);
        return Boolean.parseBoolean(value);
    }
    private static String join(List<Integer> list) { return String.join(",", list.stream().map(Object::toString).toList()); }
    private List<Integer> integers(String key) {
        String value = text(key);
        return value.isEmpty() ? List.of() : Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
    }
}
