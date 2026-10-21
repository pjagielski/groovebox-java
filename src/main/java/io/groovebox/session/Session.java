package io.groovebox.session;

import io.groovebox.audio.AudioEngine;
import io.groovebox.audio.Sample;
import io.groovebox.audio.SampleBank;
import io.groovebox.audio.WavSampleLoader;
import io.groovebox.livecode.LiveCode;
import io.groovebox.livecode.LiveCodeException;
import io.groovebox.midi.MidiFileReader;
import io.groovebox.model.DrumTrack;
import io.groovebox.model.Drum;
import io.groovebox.model.LoopTrack;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Progression;
import io.groovebox.model.Scale;
import io.groovebox.model.Song;
import io.groovebox.model.Track;
import io.groovebox.synth.DelaySync;
import io.groovebox.synth.EffectParams;
import io.groovebox.synth.PitchSynth;
import io.groovebox.synth.SynthSetting;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * A jam as it is being made, with no window onto it: its tracks, the drum grid and the code that
 * draws it, the tempo, the loop's length, the key and the chords, and what an undo goes back to.
 * Every change that is heard hands the engine a new {@link AudioEngine.Jam}, which it picks up at
 * the next loop; a front end only edits this and shows it.
 *
 * <p>Single-threaded, like the window that edits it. The one thing another thread reads is the
 * published jam, which is why that alone sits in an atomic reference.
 */
public final class Session {
    public static final int BEATS_PER_BAR = 4;

    private final AtomicReference<AudioEngine.Jam> jam = new AtomicReference<>();
    // what Ctrl+Z walks back through: the notes, the grid and the code, as they were before a change
    private final EditHistory<Snapshot> history = new EditHistory<>(64);
    // every MIDI file read so far, and every window's notes over the loop as long as it is now
    private final Map<Path, Sequence> sequences = new HashMap<>();
    private final Map<MidiWindow, List<Note>> windows = new HashMap<>();
    private double windowsLengthBeats;
    // every loop file read so far, so picking one again does not read it again
    private final Map<Path, Sample> loopAudio = new HashMap<>();

    private JamConfig jamConfig;
    private Path drumSamplesFolder;
    private TrackList tracks;
    private final List<GridRow> rows = new ArrayList<>();
    private String code = "";
    private double bpm = 120;
    private double bars = 8;
    private float masterGain = 1;
    private boolean masterMuted;
    // the key the jam is in: what the roll shades its rows and keys by
    private Scale scale = new Scale(0, Scale.Mode.ANY);
    // the chords the jam goes round, one to a bar, as they are said: Cm Ab Eb Bb
    private String chords = "";
    // the jam config's synth, for a track no instrument plays: the drum track's notes are samples
    private PitchSynth fallbackSynth;
    // whether an external synth is there to take the tracks that go out
    private boolean externalConnected;
    // how many changes are under way that will hand the jam over once, when the last one is done
    private int holding;

    /**
     * What an undo puts back: the tracks with their notes, which of them was selected, the grid and
     * the code. A gain, a mute, the tempo and the synth's knobs are performance rather than work,
     * and are left where the hand put them.
     */
    private record Snapshot(List<StudioTrack> tracks, int selected, List<GridRow> rows, String code) {
    }

    /**
     * Everything a jam config says: MIDI file and window, the file's tempo, drums and synth. The
     * jam before it is gone, and so is its history: an undo never brings back another jam's tracks.
     */
    public void load(Path config) throws Exception {
        JamConfig next = JamConfig.fromFile(config);
        Path file = legacyMidiFile(config, next.file());
        Sequence nextSequence = sequenceOf(file);
        PitchSynth nextSynth = Sounds.resolveSynth(next.synth());
        Instrument nextInstrument = Instrument.of(next.synth());
        nextInstrument.setMidi(next.midiChannelIndex(), 74, 64);
        if (next.delayMix() > 0 && nextInstrument.setting() != null) {
            // a jam can ask for its delay to be heard from the start, rather than leaving it at
            // nothing until a hand finds the knob
            SynthSetting starting = nextInstrument.setting();
            nextInstrument.set(new SynthSetting(starting.preset(), starting.params(),
                    starting.effects().withDelayMix((float) next.delayMix()), starting.delaySync()));
        }
        List<DrumTrack> pattern = Sounds.drumPatterns().get(next.drums().toLowerCase(Locale.ROOT));
        if (pattern == null) {
            throw new IllegalArgumentException("Unknown drums \"" + next.drums() + "\", expected one of "
                    + Sounds.drumPatterns().keySet());
        }

        changing(() -> {
            history.forget();
            jamConfig = next;
            tracks = TrackList.startingWith(trackName(nextSequence, next.trackIndex()),
                    new MidiWindow(file, next.trackIndex(), next.startBar()), nextInstrument);
            fallbackSynth = nextSynth;
            rows.clear();
            pattern.forEach(track -> rows.add(GridRow.fromTrack(track)));
            setBpm(MidiFileReader.readTempo(nextSequence));
            bars = next.bars();
            guessScale();
        });
    }

    /** A fresh workspace with an editable drum kit, no hits or melodies, and nothing to undo. */
    public void startEmpty() {
        changing(() -> {
            history.forget();
            jamConfig = null;
            tracks = TrackList.drumsOnly();
            fallbackSynth = Sounds.resolveSynth(DemoJam.LEAD_SYNTH);
            rows.clear();
            for (Drum drum : List.of(Drum.KICK, Drum.SNARE, Drum.CLOSED_HAT, Drum.OPEN_HAT)) {
                rows.add(GridRow.fromTrack(new DrumTrack(drum, "................", 1.0f)));
            }
            code = "";
            chords = "";
            masterGain = 1; masterMuted = false;
            scale = new Scale(0, Scale.Mode.ANY);
            setBpm(120);
            bars = 8;
        });
    }

    private static Path legacyMidiFile(Path config, Path file) {
        if (file.isAbsolute()) return file.normalize();
        Path folder = config.toAbsolutePath().normalize().getParent();
        // Old MusicJam configs use project-relative src/main/resources paths, even when the
        // config itself is inside that directory. Try its ancestors before the current folder.
        for (int level = 0; folder != null && level < 4; level++, folder = folder.getParent()) {
            Path candidate = folder.resolve(file).normalize();
            if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
        }
        return file.toAbsolutePath().normalize();
    }

    /** A jam of the tracks given, the grid empty and nothing to undo: where a jam starts that no config describes. */
    public void startWith(TrackList next, PitchSynth fallback) {
        changing(() -> {
            history.forget();
            tracks = next;
            masterGain = 1; masterMuted = false;
            fallbackSynth = fallback;
            rows.clear();
        });
    }

    /**
     * A jam made in code rather than read from a config: its tracks, the grid drawn from
     * {@code pattern}, its tempo and its length. Nothing to undo, and no config behind it.
     */
    public void startWith(TrackList next, PitchSynth fallback, List<DrumTrack> pattern, double nextBpm,
                          double nextBars) {
        changing(() -> {
            history.forget();
            jamConfig = null;
            tracks = next;
            masterGain = 1; masterMuted = false;
            fallbackSynth = fallback;
            rows.clear();
            pattern.forEach(track -> rows.add(GridRow.fromTrack(track)));
            setBpm(nextBpm);
            bars = nextBars;
            guessScale();
        });
    }

    /** The jam as the engine was last handed it; null until there is a jam. */
    public AudioEngine.Jam jam() {
        return jam.get();
    }

    /** The config the jam was loaded from, for what it says about the world outside it; null for a jam made in code. */
    public JamConfig config() {
        return jamConfig;
    }

    /** The custom kit used by the box, kept with the project so its sounds can be restored. */
    public Path drumSamplesFolder() { return drumSamplesFolder; }

    public void setDrumSamplesFolder(Path folder) { drumSamplesFolder = folder; }

    /** Writes the whole workspace, including the instruments' current knobs, to YAML or a legacy properties file. */
    public void save(Path file) throws Exception {
        SessionFile.write(this, file);
    }

    /** Opens a complete workspace or an older single-MIDI config, returning its prepared custom kit, or null. */
    public SampleBank open(Path file) throws Exception {
        var properties = SessionFile.readDocument(file);
        Session draft = new Session();
        SampleBank preparedKit = null;
        if (!properties.containsKey("session.version")) {
            draft.load(file);
        } else {
            SessionFile.State state = SessionFile.read(file, properties);
            draft.tracks = TrackList.drumsOnly();
            draft.tracks.restore(state.tracks(), state.selected());
            draft.rows.addAll(state.rows());
            draft.bpm = state.bpm(); draft.bars = state.bars();
            draft.masterGain = state.masterGain(); draft.masterMuted = state.masterMuted();
            draft.scale = state.scale(); draft.chords = state.chords(); draft.code = state.code();
            draft.drumSamplesFolder = state.drumFolder();
            draft.fallbackSynth = Sounds.resolveSynth(DemoJam.LEAD_SYNTH);
            if (state.drumFolder() != null) preparedKit = SampleBank.loadKit(state.drumFolder(), AudioEngine.DEFAULT_SAMPLE_RATE);
            for (StudioTrack track : state.tracks()) {
                if (track instanceof StudioTrack.Melody melody && melody.source() instanceof MidiWindow window) {
                    draft.sequenceOf(window.file());
                }
            }
            // Rendering the draft reads every MIDI source before the current workspace changes.
            draft.publish();
        }
        changing(() -> {
            history.forget();
            tracks = draft.tracks; rows.clear(); rows.addAll(draft.rows);
            bpm = draft.bpm; bars = draft.bars; scale = draft.scale;
            masterGain = draft.masterGain; masterMuted = draft.masterMuted;
            chords = draft.chords; code = draft.code; jamConfig = draft.jamConfig;
            fallbackSynth = draft.fallbackSynth; drumSamplesFolder = draft.drumSamplesFolder;
            sequences.clear(); sequences.putAll(draft.sequences);
            windows.clear(); windows.putAll(draft.windows); windowsLengthBeats = draft.windowsLengthBeats;
            loopAudio.clear(); loopAudio.putAll(draft.loopAudio);
        });
        return preparedKit;
    }

    public TrackList tracks() {
        return tracks;
    }

    // ---- tempo, loop, key and chords

    public double bpm() {
        return bpm;
    }

    /** The local stereo output's level, applied after the global effects. */
    public float masterGain() { return masterGain; }

    public boolean masterMuted() { return masterMuted; }

    public void setMasterGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0 || gain > 1) throw new IllegalArgumentException("Invalid master gain");
        masterGain = gain;
        publish();
    }

    public void setMasterMuted(boolean muted) { masterMuted = muted; publish(); }

    /** A new tempo, heard at once; a delay locked to the beat follows it, on every track. */
    public void setBpm(double next) {
        bpm = next;
        retimeDelays();
        publish();
    }

    /** How long the loop is, in bars: a quarter of one, or thirty-two. */
    public double bars() {
        return bars;
    }

    public void setBars(double next) {
        bars = next;
        publish();
    }

    public double loopBeats() {
        return bars * BEATS_PER_BAR;
    }

    public Scale scale() {
        return scale;
    }

    /** The key the jam is in. Nothing is heard differently: it is what the roll is shaded by. */
    public void setScale(Scale next) {
        scale = next;
    }

    /**
     * The key the jam's melodies are in, as far as their notes tell, taken as the jam's. A jam gets
     * one this way when it is loaded; a front end can ask again, for a line written since.
     */
    public Scale guessScale() {
        List<Note> melodies = new ArrayList<>();
        for (StudioTrack track : tracks.tracks()) {
            if (track instanceof StudioTrack.Melody melody) {
                melodies.addAll(notesOf(melody.source(), loopBeats()));
            }
        }
        scale = Scale.guess(melodies);
        return scale;
    }

    public String chords() {
        return chords;
    }

    public void setChords(String next) {
        chords = next;
    }

    /** The chords as they parse; a word that is not a chord names nothing. */
    public Progression progression() {
        return Progression.parse(chords);
    }

    /**
     * The selected melody played as the chords of its bars: every note the bar's chord holds
     * becomes that chord, the rest are left as they are. An edit like any other, so an undo takes
     * it back. False when there are no chords, or no melody is selected.
     */
    public boolean chordIt() {
        Progression named = progression();
        if (named.isEmpty() || !(tracks.selected() instanceof StudioTrack.Melody melody)) {
            return false;
        }
        List<Note> line = notesOf(melody.source(), loopBeats());
        changeSource(new MelodySource.OwnNotes(NoteEdits.chords(line, named, BEATS_PER_BAR), melody.window()));
        return true;
    }

    // ---- the grid and its code

    public List<GridRow> rows() {
        return List.copyOf(rows);
    }

    public String code() {
        return code;
    }

    /** The code as it is typed. Nothing changes until it is run. */
    public void setCode(String next) {
        code = next;
    }

    /** Draws the code into the grid. Code that does not parse changes nothing, and says why. */
    public void runCode() throws LiveCodeException {
        List<GridRow> fromCode = GridRow.fromCode(LiveCode.parse(code));
        remember();
        redraw(fromCode);
        publish();
    }

    /**
     * The grid as {@code drawn}, a row drawn again under the same label keeping the sound a hand
     * gave it: its channel, its accents' depth, its tuning.
     */
    private void redraw(List<GridRow> drawn) {
        List<GridRow> before = List.copyOf(rows);
        rows.clear();
        for (GridRow row : drawn) {
            rows.add(before.stream().filter(old -> old.label().equals(row.label())).findFirst()
                    .map(row::soundingAs).orElse(row));
        }
    }

    /** rest -> x -> X -> o -> rest; any other gain, as drawn from code, is taken out with one click. */
    public void cycleAccent(int row, int step) {
        remember();
        float current = rows.get(row).accents()[step];
        float next = current == 0.0f ? 0.8f : current == 0.8f ? 1.0f : current == 1.0f ? 0.5f : 0.0f;
        rows.set(row, rows.get(row).withAccent(step, next));
        publish();
    }

    /**
     * A grid row's sound changed by a knob: its level, its accents' depth, its decay or its tuning.
     * Heard at once, and not a step of its own for an undo to walk back, as a knob of the synth is
     * not. The row's channel needs none of this: its effects are set on it and heard as they are.
     */
    public void changeRow(int row, UnaryOperator<GridRow> change) {
        rows.set(row, change.apply(rows.get(row)));
        publish();
    }

    // ---- the tracks

    /**
     * A track muted or let back in. Performance rather than work, as in the studio's list: heard
     * at once, since the engine follows a track's gain block by block, and not a step for an undo.
     */
    public void setMuted(int track, boolean muted) {
        tracks.replace(track, tracks.get(track).withMuted(muted));
        publish();
    }

    /** A track's level, heard at once and, like a mute, not a step for an undo. */
    public void setGain(int track, float gain) {
        tracks.replace(track, tracks.get(track).withGain(Math.max(0.0f, Math.min(1.0f, gain))));
        publish();
    }

    /** A track soloed or let go: while any is, only the soloed ones are heard. */
    public void setSoloed(int track, boolean soloed) {
        tracks.replace(track, tracks.get(track).withSoloed(soloed));
        publish();
    }

    /** Whether {@code track} is heard as the list stands: not muted, and soloed if anything is. */
    public boolean heard(int track) {
        boolean anySoloed = tracks.tracks().stream().anyMatch(StudioTrack::soloed);
        StudioTrack shown = tracks.get(track);
        return !shown.muted() && (!anySoloed || shown.soloed());
    }

    /** The selected melody's notes changed: the editor always shows the selected track. */
    public void changeSource(MelodySource next) {
        remember();
        int index = tracks.selectedIndex();
        if (tracks.get(index) instanceof StudioTrack.Melody melody) {
            tracks.replace(index, melody.withSource(next));
            publish();
        }
    }

    /** A MIDI track's source and range changed together, keeping its instrument, mix and identity. */
    public void changeMidiWindow(int track, MidiWindow next) throws Exception {
        if (!(tracks.get(track) instanceof StudioTrack.Melody melody)) {
            throw new IllegalArgumentException("Choose a melody track");
        }
        MidiWindow window = next.withFile(next.file().toAbsolutePath().normalize());
        Sequence sequence = sequenceOf(window.file());
        List<Note> notes = MidiFileReader.readTrack(sequence, window.trackIndex());
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("The selected MIDI track has no notes");
        }
        int fileBars = (int) Math.ceil(sequence.getTickLength() / (double) sequence.getResolution() / BEATS_PER_BAR);
        if (window.startBeat() >= fileBars * (double) BEATS_PER_BAR || window.endBeat() > fileBars * (double) BEATS_PER_BAR) {
            throw new IllegalArgumentException("The range must fit within the MIDI file's " + fileBars + " bars");
        }
        if (window.equals(melody.source())) {
            return;
        }
        remember();
        tracks.replace(track, melody.withSource(window));
        publish();
    }

    /** The selected loop's file, bars or slices changed: the same track, playing something else. */
    public void changeLoop(StudioTrack.Loop next) {
        changeLoop(tracks.selectedIndex(), next);
    }

    /** The loop at {@code track} changed - its file, bars or slices - whichever track is selected. */
    public void changeLoop(int track, StudioTrack.Loop next) {
        if (tracks.get(track) instanceof StudioTrack.Loop) {
            remember();
            tracks.replace(track, next);
            publish();
        }
    }

    /** A track taken out of the jam; the drum track cannot be, there being one grid. Undone like any edit. */
    public void removeTrack(int track) {
        if (tracks.canRemove(track)) {
            remember();
            tracks.remove(track);
            publish();
        }
    }

    /**
     * Another recording in the loop at {@code track}: the same track, through the same channel
     * and at the same level, cut as it was cut, as many bars long as its own name or length says.
     */
    public void changeLoopFile(int track, Path file) throws Exception {
        if (!(tracks.get(track) instanceof StudioTrack.Loop was)) {
            return;
        }
        StudioTrack.Loop fresh = newLoop(file);
        StudioTrack.Slicing cut = was.slicing().on()
                ? StudioTrack.Slicing.of(fresh.audio(), was.slicing().beats(), was.slicing().toHits(),
                fresh.bars() * BEATS_PER_BAR, List.of())
                : StudioTrack.Slicing.NONE;
        changeLoop(track, new StudioTrack.Loop(fresh.name(), was.gain(), was.muted(), was.soloed(), file,
                fresh.bars(), fresh.audio(), cut, was.channel(), was.delaySync()));
    }

    /**
     * The grid drawn from a jam's named drum pattern - "disco", "house" - in place of the one
     * there. A row of the same drum keeps the sound a hand gave it, as when code is run again.
     */
    public void usePattern(String name) {
        List<DrumTrack> pattern = Sounds.drumPatterns().get(name);
        if (pattern == null) {
            throw new IllegalArgumentException("Unknown drums \"" + name + "\", expected one of "
                    + Sounds.drumPatterns().keySet());
        }
        remember();
        redraw(pattern.stream().map(GridRow::fromTrack).toList());
        publish();
    }

    /** The names of the drum patterns a jam can use, in order. */
    public static List<String> patternNames() {
        return Sounds.drumPatterns().keySet().stream().sorted().toList();
    }

    /** A track added at the end of the list, as "+ Melody" and "+ Loop" add one: undone like any edit. */
    public void addTrack(StudioTrack track) {
        remember();
        tracks.add(track);
        publish();
    }

    /** Adds only the selected file parts and ranges as one edit, after validating every selection. */
    public int addMidiWindows(List<MidiWindow> selected) throws Exception {
        List<MidiWindow> imported = new ArrayList<>();
        for (MidiWindow chosen : selected) {
            MidiWindow window = chosen.withFile(chosen.file().toAbsolutePath().normalize());
            Sequence sequence = sequenceOf(window.file());
            if (MidiFileReader.readTrack(sequence, window.trackIndex()).isEmpty()) {
                throw new IllegalArgumentException("The selected MIDI track has no notes");
            }
            imported.add(window);
        }
        if (imported.isEmpty()) return 0;
        changing(() -> {
            remember();
            for (MidiWindow window : imported) {
                String name = unusedName(trackName(sequences.get(window.file()), window.trackIndex()));
                tracks.add(newMelody().named(name).withSource(window));
            }
        });
        return imported.size();
    }

    /** Adds the note tracks of the chosen MIDI files as one edit, leaving the jam's tempo and loop in place. */
    public int addMidiFiles(List<Path> files) throws Exception {
        List<MidiWindow> imported = new ArrayList<>();
        // Read every file before touching the tracks, so a bad file cannot leave half an import.
        for (Path chosen : files) {
            Path file = chosen.toAbsolutePath().normalize();
            Sequence sequence = sequenceOf(file);
            int before = imported.size();
            for (int index = 0; index < sequence.getTracks().length; index++) {
                if (!MidiFileReader.readTrack(sequence, index).isEmpty()) {
                    imported.add(new MidiWindow(file, index, 0));
                }
            }
            if (imported.size() == before) {
                throw new IllegalArgumentException("No tracks with notes in " + file.getFileName());
            }
        }
        return addMidiWindows(imported);
    }

    /**
     * A knob of the selected loop's channel. The settings go straight into the channel the engine
     * is already reading, so they are heard in the pass under way rather than in the one after it;
     * only the delay's division is kept on the track.
     */
    public void changeChannel(EffectParams effects, String division) {
        if (!(tracks.selected() instanceof StudioTrack.Loop loop)) {
            return;
        }
        loop.channel().setParams(effects);
        if (!division.equals(loop.delaySync())) {
            tracks.replace(tracks.selectedIndex(), loop.lockedTo(division));
        }
    }

    /**
     * What "+ Melody" makes: an empty line to write in the roll, played by a synth of its own from
     * the patch the first melody started from, and sent out on a channel nothing else uses. It is
     * not added: the caller adds it, where it wants it.
     */
    public StudioTrack.Melody newMelody() {
        StudioTrack.Melody first = tracks.firstMelody();
        String patch = first != null && first.instrument() != null && first.instrument().setting() != null
                && first.instrument().setting().preset() != null
                ? first.instrument().setting().preset() : jamConfig != null ? jamConfig.synth() : DemoJam.LEAD_SYNTH;
        Instrument instrument = Instrument.of(patch);
        instrument.setMidi(unusedChannel(), 74, 64);
        return new StudioTrack.Melody(unusedName("Melody"), 1.0f, false,
                new MelodySource.OwnNotes(List.of(), null), instrument);
    }

    /**
     * What "Duplicate" makes of a track: the same line or the same audio, under a name of its own,
     * to work on without losing what it was copied from. A melody's instrument is copied rather
     * than shared, and the copy is sent out on a channel nothing else is using - two tracks on one
     * channel would reach an external synth as one part. Null for the drum track, which plays the
     * grid, there being one grid.
     */
    public StudioTrack duplicate(StudioTrack track) {
        // "Bass copy", not "Bass 2": a track named for the MIDI track it came from would otherwise
        // be copied into a name that reads like another track of the file
        String name = unusedName(track.name() + " copy");
        return switch (track) {
            case StudioTrack.Melody melody -> {
                Instrument copy = melody.instrument() == null ? null : melody.instrument().copy();
                if (copy != null) {
                    copy.setMidi(unusedChannel(), copy.controller(), copy.filter());
                }
                yield new StudioTrack.Melody(name, melody.gain(), melody.muted(), melody.source(), copy);
            }
            case StudioTrack.Loop loop -> new StudioTrack.Loop(name, loop.gain(), loop.muted(), loop.file(),
                    loop.bars(), loop.audio(), loop.slicing());
            case StudioTrack.Drums drums -> null;
        };
    }

    /** What "+ Loop" makes of an audio file: a track as many bars long as its name or length says. */
    public StudioTrack.Loop newLoop(Path file) throws Exception {
        Sample audio = loopOf(file);
        double loopBars = LoopBars.guess(file, audio.frameCount(), AudioEngine.DEFAULT_SAMPLE_RATE,
                BEATS_PER_BAR, bpm);
        return new StudioTrack.Loop(unusedName(loopName(file)), 1.0f, false, file, loopBars, audio);
    }

    // ---- what the tracks are made of

    public Sequence sequenceOf(Path file) throws Exception {
        Sequence known = sequences.get(file);
        if (known == null) {
            known = MidiSystem.getSequence(file.toFile());
            sequences.put(file, known);
        }
        return known;
    }

    /** The parts with notes in a file, with their names and occupied bars for a source chooser. */
    public List<MidiPart> midiParts(Path file) throws Exception {
        Sequence sequence = sequenceOf(file.toAbsolutePath().normalize());
        List<MidiPart> parts = new ArrayList<>();
        for (int index = 0; index < sequence.getTracks().length; index++) {
            List<Note> notes = MidiFileReader.readTrack(sequence, index);
            if (!notes.isEmpty()) {
                int first = (int) (notes.getFirst().beat() / BEATS_PER_BAR);
                int end = (int) Math.ceil(notes.stream().mapToDouble(note -> note.beat() + note.durationBeats())
                        .max().orElseThrow() / BEATS_PER_BAR);
                parts.add(new MidiPart(index, trackName(sequence, index), notes.size(), first, end));
            }
        }
        return List.copyOf(parts);
    }

    public Sample loopOf(Path file) throws Exception {
        Sample known = loopAudio.get(file);
        if (known == null) {
            known = WavSampleLoader.load(file, AudioEngine.DEFAULT_SAMPLE_RATE);
            loopAudio.put(file, known);
        }
        return known;
    }

    /**
     * A melody's notes over a loop of {@code lengthBeats}: its own, or a window's read from its
     * file once for each loop length. Notes past the end of a shorter loop are kept but not played.
     */
    public List<Note> notesOf(MelodySource source, double lengthBeats) {
        if (source instanceof MelodySource.OwnNotes own) {
            return NoteEdits.within(own.notes(), lengthBeats);
        }
        if (lengthBeats != windowsLengthBeats) {
            windows.clear();
            windowsLengthBeats = lengthBeats;
        }
        return windows.computeIfAbsent((MidiWindow) source, key -> key.notes(sequences.get(key.file()), lengthBeats));
    }

    // ---- undo

    /** Called before a change worth undoing. */
    public void remember() {
        if (tracks != null) {
            history.remember(snapshot());
        }
    }

    /** Goes back to before the last change; false when there was none to go back over. */
    public boolean undo() {
        return restore(history.undo(snapshot()));
    }

    public boolean redo() {
        return restore(history.redo(snapshot()));
    }

    private Snapshot snapshot() {
        return new Snapshot(tracks.tracks(), tracks.selectedIndex(), List.copyOf(rows), code);
    }

    private boolean restore(Snapshot state) {
        if (state == null) {
            return false;
        }
        changing(() -> {
            tracks.restore(state.tracks(), state.selected());
            rows.clear();
            rows.addAll(state.rows());
            code = state.code();
        });
        return true;
    }

    // ---- handing the jam over

    /** Whether there is an external synth to send the tracks that go out to; without one they play here. */
    public void setExternalConnected(boolean next) {
        externalConnected = next;
        publish();
    }

    /**
     * Several changes made as one: the engine is handed the jam once, when they are all done,
     * rather than half-made after each. Nothing is handed over if one of them fails.
     */
    public void changing(Runnable change) {
        holding++;
        try {
            change.run();
        } finally {
            holding--;
        }
        publish();
    }

    /**
     * Hands the engine a new jam. Its notes are picked up when the next loop starts, its tempo and
     * loop length at once.
     */
    public void publish() {
        if (holding > 0 || tracks == null) {
            return;
        }
        double lengthBeats = loopBeats();
        Song song = tracks.song(bpm, BEATS_PER_BAR, lengthBeats, List.copyOf(rows), this::notesOf);
        // a synth and a MIDI channel for every track of the song, which has one for each row of the grid
        List<PitchSynth> synths = new ArrayList<>();
        List<Integer> channels = new ArrayList<>();
        for (StudioTrack track : tracks.tracks()) {
            int played = track instanceof StudioTrack.Drums ? Math.max(1, rows.size()) : 1;
            // each melody by its own instrument; the drums' notes are samples, whatever synth they are given
            PitchSynth synth = track instanceof StudioTrack.Melody melody && melody.instrument() != null
                    ? melody.instrument().synth() : fallbackSynth;
            // a track sent out while no device is connected is played here, rather than not at all
            int channel = externalConnected && track instanceof StudioTrack.Melody melody
                    && melody.instrument() != null && melody.instrument().external()
                    ? melody.instrument().channel() : AudioEngine.Jam.HERE;
            for (int copy = 0; copy < played; copy++) {
                synths.add(synth);
                channels.add(channel);
            }
        }
        jam.set(new AudioEngine.Jam(song, synths, channels, masterMuted ? 0 : masterGain));
    }

    /**
     * Whether the jam holds edits the loop being heard does not play yet. Tempo, loop length and
     * the tracks' gains and mutes are not among them: the engine takes those up at once.
     */
    public boolean waiting(Song heard) {
        Song next = jam.get().song();
        return heard != next && !atFullGain(heard.tracks()).equals(atFullGain(next.tracks()));
    }

    /** The tracks as they would be with every gain up, so that only their notes are compared. */
    private static List<Track> atFullGain(List<Track> tracks) {
        return tracks.stream().map(track -> (Track) switch (track) {
            case DrumTrack drums -> new DrumTrack(drums.drum(), drums.steps(), 1.0f);
            case MelodyTrack melody -> new MelodyTrack(melody.notes(), melody.patternLengthBeats(), 1.0f);
            case LoopTrack loop -> new LoopTrack(loop.audio(), loop.bars(), 1.0f);
        }).toList();
    }

    /**
     * Every delay locked to the beat, at the tempo as it is now: each melody's synth, and each
     * loop's channel, whether its knobs are on show or not.
     */
    private void retimeDelays() {
        if (tracks == null) {
            return;
        }
        for (StudioTrack track : tracks.tracks()) {
            switch (track) {
                case StudioTrack.Melody melody when melody.instrument() != null
                        && melody.instrument().setting() != null ->
                        melody.instrument().set(melody.instrument().setting().at(bpm));
                case StudioTrack.Loop loop -> loop.channel().setParams(
                        DelaySync.named(loop.delaySync()).timed(loop.channel().params(), bpm));
                default -> {
                }
            }
        }
    }

    /**
     * The lowest MIDI channel no melody goes out on, so a new track can be sent out without two
     * sharing one; channel 10, which General MIDI keeps for drums, is left alone.
     */
    private int unusedChannel() {
        List<Integer> taken = tracks.tracks().stream()
                .filter(track -> track instanceof StudioTrack.Melody melody && melody.instrument() != null)
                .map(track -> ((StudioTrack.Melody) track).instrument().channel())
                .toList();
        for (int candidate = 0; candidate < 16; candidate++) {
            if (candidate != 9 && !taken.contains(candidate)) {
                return candidate;
            }
        }
        return 0;
    }

    /** {@code name}, or "name 2", "name 3" and on when a track already goes by it. */
    private String unusedName(String name) {
        List<String> taken = tracks.tracks().stream().map(StudioTrack::name).toList();
        String candidate = name;
        for (int number = 2; taken.contains(candidate); number++) {
            candidate = name + " " + number;
        }
        return candidate;
    }

    /** A MIDI track by the name its file gives it, or by its number when the file gives none. */
    private static String trackName(Sequence sequence, int trackIndex) {
        String name = trackIndex < sequence.getTracks().length
                ? MidiFileReader.trackName(sequence, trackIndex).replace("\0", "").trim() : "";
        return name.isEmpty() ? "Track " + trackIndex : name;
    }

    /** A loop track's name from its file's: without the extension, and without the tempo it is named for. */
    private static String loopName(Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.[^.]+$", "")
                .replaceFirst("^\\d{2,3}[_-]\\d{1,3}[_-]", "");
        return name.isEmpty() ? "Loop" : name;
    }
}
