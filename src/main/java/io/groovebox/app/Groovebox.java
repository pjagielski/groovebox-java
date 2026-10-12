package io.groovebox.app;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import io.groovebox.model.Drum;
import javafx.stage.Stage;
import io.groovebox.session.Sounds;
import io.groovebox.audio.AudioEngine;
import io.groovebox.audio.MixLevels;
import io.groovebox.audio.Sample;
import io.groovebox.audio.SampleBank;
import io.groovebox.model.Note;
import io.groovebox.session.GridRow;
import io.groovebox.session.Instrument;
import io.groovebox.session.DemoJam;
import io.groovebox.session.Playback;
import io.groovebox.session.Session;
import io.groovebox.session.MidiWindow;
import io.groovebox.session.MidiPart;
import io.groovebox.session.StudioTrack;
import io.groovebox.controls.Glyph;
import io.groovebox.controls.PanelKnob;
import io.groovebox.controls.StudioIcon;
import io.groovebox.controls.StudioPanels;
import io.groovebox.synth.ChannelFilter;
import io.groovebox.synth.EffectParams;
import io.groovebox.synth.LiveEffects;
import io.groovebox.synth.SynthParams;
import io.groovebox.synth.SynthSetting;
import io.groovebox.synth.Waveform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The jam as a box to play rather than a desk to write at: a screen, eight knobs, sixteen pads
 * and a chip per track. The same {@link Session} and {@link Playback} as the studio's, so what is
 * heard here is what the studio would play.
 *
 * <p>A chip per track, as the studio has them, picks what the rest is about: the drums, whose
 * selected row's steps the pads are and a press on one cycles, or a melody, whose bar the pads
 * show note by note and a press on one sounds. Which drum row is a matter for the screen, where
 * the whole kit is: a click on a row picks it, and so do the up and down arrows. The knobs
 * are the chip's: a drum row's level, a melody's synth. The mixer shares the same space and
 * holds the channel faders and global effects. Play and tempo stay in reach in both views.
 * Space plays and stops.
 *
 * <p>Starts from {@code --config <file>} when given one - its melody, its tempo and its drums -
 * and otherwise with an empty workspace. {@link DemoJam} can be loaded from the title bar.
 */
public final class Groovebox extends Application {
    // how long a melody pad sounds its note: a voice is made with its length, not let go of
    private static final double AUDITION_SECONDS = 0.4;
    private static final double KNOBS_WIDTH = 290;
    private static final double GAP = 20;
    /** How wide the box is inside its edge: the screen and the knobs beside it. */
    private static final double INSIDE = Screen.WIDTH + GAP + KNOBS_WIDTH;
    private static final double PLAY_WIDTH = 64;
    private static final double STRIP_GAP = 10;
    private static final double LONGEST_DECAY_MILLIS = 2000;
    // a loop's filter all the way open is no filter at all, which is where a loop starts
    private static final double OPEN_CUTOFF = 18000;
    /** How a loop can be cut, each a length of slice in beats: the eighth is where one starts. */
    private static final Map<String, Double> CUTS = new LinkedHashMap<>();
    private static final double FIRST_CUT = 0.5;
    private static final int PEAK_BUCKETS = 2048;

    static {
        CUTS.put("1/4", 1.0);
        CUTS.put("1/8", 0.5);
        CUTS.put("1/16", 0.25);
    }

    private final Session session = new Session();
    private Playback playback;
    private Part selected = new Part.DrumRow(0);
    // the drum row the drums chip goes back to, so leaving the drums for a melody does not lose it
    private int drumRow;
    // the page of sixteen shown while the jam is stopped, turned with the arrows; while it plays,
    // the page is the one the playhead is on
    private int viewPage;
    // the slice of the selected loop picked on the screen, which a pad then plays; -1 for none
    private int chosenSlice = -1;
    // where the last loop came from, which is where the next chooser opens
    private Path loopFolder;
    // the next MIDI chooser opens where the last import came from
    private Path midiFolder;
    private Path drumFolder;
    private Path projectFile;
    private SampleBank defaultDrumKit;
    // each loop's waveform as the screen draws it, worked out once per recording
    private final Map<Sample, float[]> peaks = new IdentityHashMap<>();

    private final Screen screen = new Screen();
    private final Pads pads = new Pads(INSIDE, this::pressed, this::otherPressed);
    private final HBox chips = new HBox(8);
    private final ScrollPane trackScroll = new ScrollPane(chips);
    private final GridPane knobs = new GridPane();
    private final Button play = new Button();
    private final Label status = new Label("stopped");
    private final Tempo tempo = new Tempo(Strip.HEIGHT, next -> {
        session.setBpm(next);
        shown = "";
    });
    private final List<Strip> strips = new ArrayList<>();
    private MixerView mixer;
    private VBox instrumentView;
    private VBox mixerPane;
    private HBox transport;
    private Node globalEffects;
    private ToggleButton instrumentTab;
    private ToggleButton mixerTab;
    private boolean mixing;
    // the knobs' second page, printed in orange under each of them; latched, not held: a mouse
    // cannot hold one button and turn a knob at the same time
    private final Button shift = new Button("shift");
    private boolean shifted;
    // what was last drawn, so a frame that changes nothing draws nothing
    private String shown = "";

    @Override
    public void start(Stage stage) throws Exception {
        defaultDrumKit = SampleBank.load(Path.of("samples"), AudioEngine.DEFAULT_SAMPLE_RATE);
        playback = new Playback(
                new AudioEngine(defaultDrumKit), session::jam);
        Path config = configPath();
        if (config != null) {
            SampleBank kit = session.open(config);
            projectFile = config.toAbsolutePath().normalize();
            drumFolder = session.drumSamplesFolder();
            if (kit != null) playback.useDrumKit(kit);
        } else {
            session.startEmpty();
        }

        Scene scene = new Scene(layout());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.SPACE) {
                togglePlayback();
                event.consume();
            } else if (event.getCode() == KeyCode.TAB && !mixing) {
                // Tab, since Shift on the keyboard already turns a knob in fine steps
                toggleShift();
                event.consume();
            }
        });
        // a handler rather than a filter: a knob with focus keeps its arrows to itself
        scene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (mixing) return;
            if (selected instanceof Part.DrumRow(int row)
                    && (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN)) {
                int next = row + (event.getCode() == KeyCode.UP ? -1 : 1);
                if (next >= 0 && next < session.rows().size()) {
                    select(new Part.DrumRow(next));
                }
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT) {
                // while it plays the page follows the playhead; stopped, the arrows turn it
                if (beat() < 0) {
                    viewPage = Math.max(0, viewPage + (event.getCode() == KeyCode.RIGHT ? 1 : -1));
                    shown = "";
                }
                event.consume();
            }
        });
        pads.setDragging(this::dragged, this::dropped);
        stage.setScene(scene);
        stage.setTitle(projectFile == null ? "Groovebox" : "Groovebox · " + projectFile.getFileName());
        stage.getIcons().setAll(StudioIcon.sizes());
        stage.setResizable(false);
        refreshJamView();
        stage.show();
        frames().start();
    }

    @Override
    public void stop() {
        strips.forEach(Strip::release);
        playback.close();
    }

    /** The jam config named by {@code --config}; null when none is. */
    private Path configPath() {
        List<String> args = getParameters().getRaw();
        int index = args.indexOf("--config");
        return index >= 0 && index + 1 < args.size() ? Path.of(args.get(index + 1)) : null;
    }

    // ---- what a hand does

    private void togglePlayback() {
        if (playback.playing()) {
            strips.forEach(Strip::release);
            playback.stop();
        } else {
            try {
                playback.play();
            } catch (Exception failure) {
                showError(failure);
            }
        }
        showTransport();
    }

    /** A pad pressed: a drum row's step cycles through its accents, a melody's note sounds. */
    private void pressed(int pad) {
        double beat = beat();
        switch (selected) {
            case Part.DrumRow(int row) -> {
                GridRow shownRow = session.rows().get(row);
                int step = Steps.page(shownRow, beat) * Steps.PADS + pad;
                if (step < shownRow.accents().length) {
                    session.cycleAccent(row, step);
                    // a pad is played, not written: heard in this pass of the loop, not the next
                    playback.takeNow();
                    shown = "";
                }
            }
            case Part.Melody(int track) -> {
                if (!(session.tracks().get(track) instanceof StudioTrack.Melody melody)
                        || melody.instrument() == null) {
                    return;
                }
                double start = melodyBar(beat) * (double) Session.BEATS_PER_BAR + pad * Steps.PAD_BEATS;
                for (Note note : session.notesOf(melody.source(), session.loopBeats())) {
                    if (note.beat() >= start - 1e-9 && note.beat() < start + Steps.PAD_BEATS - 1e-9
                            && Steps.pitchOf(note) >= 0) {
                        audition(melody.instrument(), Steps.pitchOf(note), note.velocity());
                    }
                }
            }
            case Part.Loop(int track) -> {
                StudioTrack.Loop loop = loopAt(track);
                List<Integer> order = loop.slicing().order();
                int step = loopPage(loop, beat) * Steps.PADS + pad;
                if (step >= order.size()) {
                    return;
                }
                int now = order.get(step);
                // the slice picked on the screen, and pressed again the step's own; with none
                // picked, the next slice round
                int next = chosenSlice >= 0 ? (now == chosenSlice ? step : chosenSlice)
                        : now < 0 ? step : (now + 1) % loop.slicing().count();
                changeStep(track, loop, step, next);
            }
        }
    }

    /**
     * The other button on a pad: a drum row's step emptied, a loop's step made a rest - or given
     * back its own slice, when it is one already. A melody's pads only sound.
     */
    private void otherPressed(int pad) {
        double beat = beat();
        switch (selected) {
            case Part.DrumRow(int row) -> {
                GridRow shownRow = session.rows().get(row);
                int step = Steps.page(shownRow, beat) * Steps.PADS + pad;
                if (step < shownRow.accents().length && shownRow.accents()[step] > 0) {
                    session.remember();
                    session.changeRow(row, r -> r.withAccent(step, 0));
                    playback.takeNow();
                    shown = "";
                }
            }
            case Part.Loop(int track) -> {
                StudioTrack.Loop loop = loopAt(track);
                int step = loopPage(loop, beat) * Steps.PADS + pad;
                if (step < loop.slicing().order().size()) {
                    changeStep(track, loop, step, loop.slicing().order().get(step) < 0 ? step : -1);
                }
            }
            case Part.Melody melody -> {
            }
        }
    }

    /** The slice a pad of the selected loop carries when dragged; nothing to drag on another view. */
    private int dragged(int pad) {
        if (!(selected instanceof Part.Loop(int track))) {
            return Pads.NOTHING;
        }
        StudioTrack.Loop loop = loopAt(track);
        int step = loopPage(loop, beat()) * Steps.PADS + pad;
        return step < loop.slicing().order().size() ? loop.slicing().order().get(step) : Pads.NOTHING;
    }

    /** A slice dropped on a pad of the selected loop: that step plays it from now on. */
    private void dropped(int pad, int slice) {
        if (!(selected instanceof Part.Loop(int track))) {
            return;
        }
        StudioTrack.Loop loop = loopAt(track);
        int step = loopPage(loop, beat()) * Steps.PADS + pad;
        if (step < loop.slicing().order().size()) {
            changeStep(track, loop, step, slice);
        }
    }

    /** A loop's step given another slice: heard in this pass, like a drum's pad. */
    private void changeStep(int track, StudioTrack.Loop loop, int step, int slice) {
        session.changeLoop(track, loop.cutInto(loop.slicing().with(step, slice)));
        playback.takeNow();
        shown = "";
    }

    /** A loop cut again into slices {@code beats} long, keeping its order where it still fits. */
    private void recut(int track, double beats) {
        StudioTrack.Loop loop = loopAt(track);
        if (loop.slicing().beats() == beats) {
            return;
        }
        session.changeLoop(track, loop.cutInto(StudioTrack.Slicing.of(loop.audio(), beats, true,
                passBeats(loop), loop.slicing().order())));
        playback.takeNow();
        chosenSlice = -1;
        viewPage = 0;
        shown = "";
    }

    /** A slice heard on its own, at the rate and through the channel it plays at in the jam. */
    private void trySlice(StudioTrack.Loop loop, int slice) {
        List<Integer> starts = loop.slicing().starts();
        int from = starts.get(slice);
        int until = slice + 1 < starts.size() ? starts.get(slice + 1) : loop.audio().frameCount();
        double passFrames = passBeats(loop) * 60.0 / session.bpm() * AudioEngine.DEFAULT_SAMPLE_RATE;
        try {
            playback.listening().audition(loop.audio(), from, until, loop.audio().frameCount() / passFrames,
                    0.9f, loop.channel());
        } catch (Exception failure) {
            showError(failure);
        }
    }

    /** "+ loop": a recording chosen from the disk, added as a track cut into eighths. */
    private void chooseLoop(Node owner) {
        Path file = pickLoop(owner);
        if (file != null) {
            addLoop(file);
        }
    }

    /** One chosen part per file, previewed and auditioned before any tracks are added. */
    private void chooseMidi(Node owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add tracks from MIDI files");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MIDI files", "*.mid", "*.midi"));
        if (midiFolder != null && Files.isDirectory(midiFolder)) {
            chooser.setInitialDirectory(midiFolder.toFile());
        }
        List<File> chosen = chooser.showOpenMultipleDialog(owner.getScene().getWindow());
        if (chosen == null || chosen.isEmpty()) {
            return;
        }
        boolean[] paused = {false};
        try {
            List<Path> files = chosen.stream().map(file -> file.toPath().toAbsolutePath().normalize()).toList();
            List<MidiWindow> selections = new ArrayList<>();
            double jamBars = session.bars();
            for (Path file : files) {
                List<MidiPart> parts = session.midiParts(file);
                if (parts.isEmpty()) throw new IllegalArgumentException("No tracks with notes in " + file.getFileName());
                var edit = MidiLoopDialog.createImport(owner.getScene().getWindow(),
                        new MidiWindow(file, parts.getFirst().trackIndex(), 0), parts, jamBars,
                        session.sequenceOf(file), session.bpm(), session.newMelody().instrument().synth(),
                        () -> {
                            if (playback.playing()) {
                                playback.stop();
                                paused[0] = true;
                                showTransport();
                            }
                        }).showAndWait();
                if (edit.isEmpty()) return;
                selections.add(edit.get().window());
                jamBars = edit.get().jamBars();
            }
            double nextBars = jamBars;
            session.changing(() -> {
                try {
                    session.addMidiWindows(selections);
                    session.setBars(nextBars);
                } catch (Exception failure) {
                    throw new IllegalArgumentException(failure.getMessage(), failure);
                }
            });
            midiFolder = files.getFirst().getParent();
            playback.takeNow();
            select(new Part.Melody(session.tracks().selectedIndex()));
            Platform.runLater(() -> trackScroll.setHvalue(1));
        } catch (Exception failure) {
            showError(failure);
        } finally {
            if (paused[0]) {
                try {
                    playback.play();
                    showTransport();
                } catch (Exception failure) {
                    showError(failure);
                }
            }
        }
    }

    /** A loop's other recording: the same track, its channel and its cut, playing something else. */
    private void changeLoopFile(Node owner, int track) {
        Path file = pickLoop(owner);
        if (file == null) {
            return;
        }
        try {
            session.changeLoopFile(track, file);
            loopFolder = file.getParent();
            playback.takeNow();
            chosenSlice = -1;
            viewPage = 0;
            select(new Part.Loop(track));
        } catch (Exception failure) {
            showError(failure);
        }
    }

    /** A MIDI part's source and bars chosen together, heard in the pass already under way. */
    private void chooseMidiWindow(Node owner, int track) {
        if (!(session.tracks().get(track) instanceof StudioTrack.Melody melody) || melody.window() == null) {
            return;
        }
        boolean[] paused = {false};
        try {
            MidiLoopDialog.create(owner.getScene().getWindow(), melody.window(),
                    session.midiParts(melody.window().file()), session.bars(),
                    session.sequenceOf(melody.window().file()), session.bpm(),
                    melody.instrument() == null ? Sounds.resolveSynth(DemoJam.LEAD_SYNTH) : melody.instrument().copy().synth(),
                    () -> {
                        if (playback.playing()) {
                            playback.stop();
                            paused[0] = true;
                            showTransport();
                        }
                    }).showAndWait().ifPresent(edit -> {
                session.changing(() -> {
                    try {
                        session.changeMidiWindow(track, edit.window());
                    } catch (Exception failure) {
                        throw new IllegalArgumentException(failure.getMessage(), failure);
                    }
                    session.setBars(edit.jamBars());
                });
                playback.takeNow();
                viewPage = 0;
                select(new Part.Melody(track));
            });
        } catch (Exception failure) {
            showError(failure);
        } finally {
            if (paused[0]) {
                try {
                    playback.play();
                    showTransport();
                } catch (Exception failure) {
                    showError(failure);
                }
            }
        }
    }

    /** A recording chosen from the disk; null when the chooser was closed with nothing picked. */
    private Path pickLoop(Node owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("A loop to play in time with the jam");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Audio files", "*.wav", "*.aif", "*.aiff", "*.au"));
        File start = loopsFolder();
        if (start != null) {
            chooser.setInitialDirectory(start);
        }
        File chosen = chooser.showOpenDialog(owner.getScene().getWindow());
        return chosen == null ? null : chosen.toPath().toAbsolutePath().normalize();
    }

    /** A track taken out, heard gone at once: the box goes back to the drums. */
    private void removeTrack(int track) {
        session.removeTrack(track);
        playback.takeNow();
        select(new Part.DrumRow(drumRow));
    }

    /** The grid drawn from another of the jams' drum patterns, heard from now on. */
    private void usePattern(String name) {
        session.usePattern(name);
        playback.takeNow();
        drumRow = 0;
        select(new Part.DrumRow(0));
    }

    /**
     * A loop added and picked, heard from where the jam is now rather than from its next loop. It
     * comes cut into eighths, played as recorded, so the pads have its slices to put in another
     * order from the start.
     */
    void addLoop(Path file) {
        try {
            StudioTrack.Loop loop = session.newLoop(file);
            loop = loop.cutInto(StudioTrack.Slicing.of(loop.audio(), FIRST_CUT, true, passBeats(loop), List.of()));
            session.addTrack(loop);
            loopFolder = file.getParent();
            playback.takeNow();
            select(new Part.Loop(session.tracks().size() - 1));
        } catch (Exception failure) {
            showError(failure);
        }
    }

    /**
     * Where the loop chooser opens: where the last loop came from, {@code --loops <dir>}, the jam's
     * {@code loopsFolder}, or loops/.
     */
    private File loopsFolder() {
        List<Path> where = new ArrayList<>();
        if (loopFolder != null) {
            where.add(loopFolder);
        }
        List<String> args = getParameters().getRaw();
        int index = args.indexOf("--loops");
        if (index >= 0 && index + 1 < args.size()) {
            where.add(Path.of(args.get(index + 1)));
        }
        if (session.config() != null && session.config().loopsFolder() != null) {
            where.add(session.config().loopsFolder());
        }
        where.add(Path.of("loops").toAbsolutePath());
        return where.stream().filter(Files::isDirectory).findFirst().map(Path::toFile).orElse(null);
    }

    /** The slice of the selected loop drawn at {@code x} on the screen; -1 for none, or another view. */
    private int sliceOnScreen(double x) {
        if (!(selected instanceof Part.Loop(int track))) {
            return -1;
        }
        StudioTrack.Loop loop = loopAt(track);
        return Screen.loopSliceAt(x, loopPage(loop, beat()), loop.slicing().order().size(),
                loop.audio().frameCount(), loop.slicing().starts());
    }

    private StudioTrack.Loop loopAt(int track) {
        return (StudioTrack.Loop) session.tracks().get(track);
    }

    private static double passBeats(StudioTrack.Loop loop) {
        return loop.bars() * Session.BEATS_PER_BAR;
    }

    /** The page of a loop's steps on the pads: the playhead's while it plays, the arrows' while not. */
    private int loopPage(StudioTrack.Loop loop, double beat) {
        int count = loop.slicing().order().size();
        int step = Steps.loopStep(beat, passBeats(loop), count);
        return step >= 0 ? step / Steps.PADS : Math.min(viewPage, Steps.pages(count) - 1);
    }

    /** The bar of the melody on the pads: the playhead's while it plays, the arrows' while not. */
    private int melodyBar(double beat) {
        int bars = Math.max(1, (int) Math.ceil(session.bars()));
        return beat >= 0 ? Steps.bar(beat) % bars : Math.min(viewPage, bars - 1);
    }

    private void audition(Instrument instrument, int pitch, float velocity) {
        try {
            playback.listening().audition(instrument.synth(), pitch, AUDITION_SECONDS, velocity);
        } catch (Exception failure) {
            showError(failure);
        }
    }

    /** A chip or a drum row picked: the screen, the pads and the knobs are about it from now on. */
    private void select(Part next) {
        if (!next.equals(selected)) {
            viewPage = 0;
            chosenSlice = -1;
        }
        selected = next;
        switch (next) {
            case Part.Melody(int track) -> session.tracks().select(track);
            case Part.Loop(int track) -> session.tracks().select(track);
            case Part.DrumRow ignored -> {
                for (int i = 0; i < session.tracks().size(); i++) {
                    if (session.tracks().get(i) instanceof StudioTrack.Drums) {
                        session.tracks().select(i);
                        break;
                    }
                }
            }
        }
        if (next instanceof Part.DrumRow(int row)) {
            drumRow = row;
        }
        showChips();
        showKnobs();
        shown = "";
    }

    // ---- drawing

    /** Every frame: the screen and the pads, redrawn when what they show has moved. */
    private AnimationTimer frames() {
        return new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (mixing) mixer.refresh(playback.live() == null ? MixLevels.SILENT : playback.live().levels());
                if (playback.live() != null && playback.live().failure().isPresent()) {
                    Throwable failure = playback.live().failure().get();
                    togglePlayback();
                    showError(failure);
                }
                double beat = beat();
                // a melody's playhead moves within a pad, so it is drawn every frame while it plays
                String key = selected + " " + (!(selected instanceof Part.DrumRow) && beat >= 0
                        ? String.valueOf(beat) : Steps.sixteenth(beat) + " " + Steps.bar(beat));
                if (key.equals(shown)) {
                    return;
                }
                shown = key;
                draw(beat);
            }
        };
    }

    private void draw(double beat) {
        switch (selected) {
            case Part.DrumRow(int row) -> {
                List<GridRow> rows = session.rows();
                screen.drawDrums(rows, row, beat);
                GridRow shownRow = rows.get(row);
                int page = Steps.page(shownRow, beat);
                Pads.Pad[] shownPads = new Pads.Pad[Steps.PADS];
                for (int pad = 0; pad < Steps.PADS; pad++) {
                    int step = page * Steps.PADS + pad;
                    float accent = step < shownRow.accents().length ? shownRow.accents()[step] : -1;
                    shownPads[pad] = accent < 0 ? Pads.Pad.OFF
                            : accent == 0 ? Pads.Pad.EMPTY : Pads.Pad.hit("", accent);
                }
                pads.show(shownPads);
                int current = Steps.current(shownRow, beat);
                pads.light(current < 0 || current / Steps.PADS != page ? -1 : current % Steps.PADS);
            }
            case Part.Melody(int track) -> {
                StudioTrack.Melody melody = (StudioTrack.Melody) session.tracks().get(track);
                List<Note> line = session.notesOf(melody.source(), session.loopBeats());
                int bars = (int) Math.ceil(session.bars());
                int bar = melodyBar(beat);
                screen.drawMelody(melody.name(), session.scale(), line, bar, bars,
                        beat < 0 ? -1 : beat % session.loopBeats());
                pads.show(Steps.melody(line, bar));
                pads.light(Steps.sixteenth(beat));
            }
            case Part.Loop(int track) -> {
                StudioTrack.Loop loop = loopAt(track);
                StudioTrack.Slicing slicing = loop.slicing();
                int page = loopPage(loop, beat);
                int current = Steps.loopStep(beat, passBeats(loop), slicing.order().size());
                float[] shownPeaks = peaks.computeIfAbsent(loop.audio(), audio -> Screen.peaks(audio, PEAK_BUCKETS));
                screen.drawLoop(loop.name(), cutName(slicing.beats()), shownPeaks, loop.audio().frameCount(),
                        slicing.starts(), slicing.order(), page, current, chosenSlice,
                        beat < 0 ? -1 : beat % passBeats(loop) / passBeats(loop));
                pads.show(Steps.loop(slicing.order(), page));
                pads.light(current >= 0 && current / Steps.PADS == page ? current % Steps.PADS : -1);
            }
        }
        var position = playback.live() == null ? null : playback.live().position();
        status.setText(position == null ? "stopped" : String.format(Locale.ROOT, "bar %d of %d",
                (int) (position.beat() / Session.BEATS_PER_BAR) + 1,
                (int) Math.ceil(position.lengthBeats() / Session.BEATS_PER_BAR)));
    }

    /** Where the jam is, in beats from the top of its loop; -1 while it is stopped. */
    private double beat() {
        var live = playback.live();
        var position = live == null ? null : live.position();
        return position == null ? -1 : position.beat();
    }

    private void showTransport() {
        boolean playing = playback.playing();
        play.setGraphic(playing ? Glyph.stop(15, Color.WHITE) : Glyph.play(17, Color.WHITE));
        play.setStyle("-fx-background-color: " + (playing ? "#a63d1d" : Look.PLAY)
                + "; -fx-background-radius: 10; -fx-padding: 0; -fx-cursor: hand;");
    }

    private void showChips() {
        chips.getChildren().clear();
        for (int track = 0; track < session.tracks().size(); track++) {
            String name = session.tracks().get(track).name().toLowerCase(Locale.ROOT);
            switch (session.tracks().get(track)) {
                case StudioTrack.Drums drums -> chips.getChildren().add(
                        chip(name, new Part.DrumRow(drumRow), selected instanceof Part.DrumRow, track));
                case StudioTrack.Melody melody -> {
                    Part part = new Part.Melody(track);
                    chips.getChildren().add(chip(name, part, part.equals(selected), track));
                }
                case StudioTrack.Loop loop -> {
                    Part part = new Part.Loop(track);
                    chips.getChildren().add(chip(name, part, part.equals(selected), track));
                }
            }
        }
    }

    /** The tracks scroll within the box, with both import buttons always in reach. */
    private Node trackBar() {
        ScrollPane scroll = trackScroll;
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinWidth(0);
        scroll.setPrefHeight(52);
        scroll.setStyle("-fx-background: " + Look.web(Look.BODY)
                + "; -fx-background-color: transparent; -fx-padding: 0;");
        HBox.setHgrow(scroll, Priority.ALWAYS);
        Button midi = addTrackButton("+ MIDI");
        midi.setTooltip(new Tooltip("Choose MIDI files, then select the track and range to add"));
        midi.setOnAction(event -> chooseMidi(midi));
        Button loop = addTrackButton("+ loop");
        loop.setOnAction(event -> chooseLoop(loop));
        HBox bar = new HBox(8, scroll, midi, loop);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static Button addTrackButton(String text) {
        Button add = new Button(text);
        add.setFocusTraversable(false);
        add.setMinWidth(Region.USE_PREF_SIZE);
        add.setStyle("-fx-background-color: transparent; -fx-border-color: " + Look.web(Look.BODY_EDGE)
                + "; -fx-border-radius: 14; -fx-text-fill: " + Look.CHIP_OFF_TEXT + "; -fx-padding: 3 14 3 14;"
                + " -fx-font-size: 13px; -fx-cursor: hand;");
        return add;
    }

    private static String cutName(double beats) {
        return CUTS.entrySet().stream().filter(cut -> cut.getValue() == beats).map(Map.Entry::getKey)
                .findFirst().orElse("uncut");
    }

    /**
     * A track's chip picks its instrument. A track not heard because of the mixer shows it
     * by its chip fading, while volume, mute and solo are adjusted in the mixer.
     */
    private Node chip(String name, Part part, boolean on, int track) {
        Button chip = new Button(name);
        chip.setFocusTraversable(false);
        chip.setContextMenu(trackMenu(chip, track));
        Tooltip.install(chip, new Tooltip("Right-click to change or remove"));
        chip.setStyle("-fx-background-color: " + (on ? Look.CHIP_ON : Look.CHIP_OFF) + "; -fx-text-fill: "
                + (on ? Look.CHIP_ON_TEXT : Look.CHIP_OFF_TEXT) + "; -fx-background-radius: 14;"
                + " -fx-padding: 3 16 3 16; -fx-font-size: 13px; -fx-cursor: hand;");
        chip.setOpacity(session.heard(track) ? 1.0 : 0.45);
        chip.setOnAction(event -> select(part));
        return chip;
    }

    /**
     * What a track's chip offers on its other button: the drums another pattern; a melody another
     * sound, or to go; a loop another recording, or to go.
     */
    private ContextMenu trackMenu(Node chip, int track) {
        ContextMenu menu = new ContextMenu();
        switch (session.tracks().get(track)) {
            case StudioTrack.Drums drums -> {
                Menu patterns = new Menu("Pattern");
                for (String name : Session.patternNames()) {
                    MenuItem pattern = new MenuItem(name);
                    pattern.setOnAction(event -> usePattern(name));
                    patterns.getItems().add(pattern);
                }
                menu.getItems().add(patterns);
            }
            case StudioTrack.Melody melody -> {
                if (melody.window() != null) {
                    MenuItem range = new MenuItem("MIDI loop...");
                    range.setOnAction(event -> chooseMidiWindow(chip, track));
                    menu.getItems().add(range);
                }
                Menu sounds = new Menu("Sound");
                ToggleGroup chosen = new ToggleGroup();
                for (String name : Sounds.synthNames()) {
                    RadioMenuItem sound = new RadioMenuItem(name);
                    sound.setToggleGroup(chosen);
                    sound.setSelected(melody.instrument() != null && name.equals(melody.instrument().patch()));
                    sound.setOnAction(event -> {
                        if (melody.instrument() != null && melody.instrument().load(name)) {
                            showKnobs();
                        }
                    });
                    sounds.getItems().add(sound);
                }
                MenuItem remove = new MenuItem("Remove");
                remove.setOnAction(event -> removeTrack(track));
                menu.getItems().addAll(sounds, new SeparatorMenuItem(), remove);
            }
            case StudioTrack.Loop loop -> {
                MenuItem change = new MenuItem("Another loop...");
                change.setOnAction(event -> changeLoopFile(chip, track));
                MenuItem remove = new MenuItem("Remove");
                remove.setOnAction(event -> removeTrack(track));
                menu.getItems().addAll(change, new SeparatorMenuItem(), remove);
            }
        }
        return menu;
    }

    /** One knob of a page: what it is called, and how it is made when it is shown. */
    private record Slot(String name, Supplier<PanelKnob> knob) {
    }

    /**
     * A part's two pages of knobs, eight to a page, null where a page has nothing. The sound page
     * is laid out alike for every part - what makes the sound, the filter's cutoff, resonance and
     * envelope, then drive, delay, reverb and crush - so a hand learns one box rather than three;
     * the shape page is what is set once rather than played: the part's own four on top, the
     * echo's time and feedback, the room's size and the duck under the kick below. A melody is
     * the exception: its oscillators and envelope are played, so they take the first page's
     * bottom row, and its drive and sends go up a row on the shape page.
     */
    private record Pages(List<Slot> sound, List<Slot> shape) {
    }

    /**
     * The selected part's knobs: the sound page, or with shift lit the shape page. Under each knob
     * is printed what it is on the other page, in orange where shift would make it so.
     */
    private void showKnobs() {
        knobs.getChildren().clear();
        Pages pages = switch (selected) {
            case Part.DrumRow(int row) -> drumPages(row);
            case Part.Loop(int track) -> loopPages(track);
            case Part.Melody(int track) -> melodyPages(track);
        };
        if (pages == null) {
            // a synth from outside the ported patches has no knobs to turn: it plays as it is
            return;
        }
        for (int index = 0; index < 8; index++) {
            Slot active = (shifted ? pages.shape() : pages.sound()).get(index);
            Slot other = (shifted ? pages.sound() : pages.shape()).get(index);
            Node dial;
            if (active != null) {
                dial = active.knob().get().node();
            } else {
                // a knob of the same size that is not there, so the grid keeps its places
                dial = StudioPanels.boxKnob(" ", 0, 1, "", 0, 0).node();
                dial.setVisible(false);
            }
            Label printed = new Label(other == null ? " " : other.name());
            printed.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                    + (shifted ? Look.web(Look.QUIET_INK) : Look.PLAY) + ";");
            VBox cell = new VBox(0, dial, printed);
            cell.setAlignment(Pos.TOP_CENTER);
            knobs.add(cell, index % 4, index / 4);
        }
    }

    /** Shift lit or let go: the knobs' other page. */
    private void toggleShift() {
        shifted = !shifted;
        showShift();
        showKnobs();
    }

    private void showShift() {
        shift.setStyle("-fx-background-color: " + (shifted ? Look.PLAY : Look.CHIP_OFF) + "; -fx-text-fill: "
                + (shifted ? "white" : Look.CHIP_OFF_TEXT) + "; -fx-background-radius: 8; -fx-padding: 3 14 3 14;"
                + " -fx-font-size: 12px; -fx-cursor: hand;");
    }

    private Pages drumPages(int row) {
        GridRow shownRow = session.rows().get(row);
        LiveEffects channel = shownRow.channel();
        double decay = shownRow.envelope().decaySeconds();
        List<Slot> sound = new ArrayList<>();
        // the tuning is in the row's notes, so the drums are taken again for it to be heard now
        sound.add(new Slot("tune", () -> knob(StudioPanels.boxBipolarKnob("Tune", -12, 12, "st", 1,
                shownRow.envelope().semitones()), value -> playRow(row, r -> r.tunedTo(value)))));
        sound.addAll(filter(channel));
        sound.add(drive(channel));
        sound.addAll(sends(channel::params, change -> effects(channel, change)));
        List<Slot> shape = new ArrayList<>();
        // the level is the row's track's gain, which the engine follows block by block
        shape.add(new Slot("level", () -> knob(StudioPanels.boxKnob("Level", 0, 1, "", 2, shownRow.gain()),
                value -> session.changeRow(row, r -> r.withGain((float) value)))));
        shape.add(new Slot("accent", () -> knob(StudioPanels.boxKnob("Accent", 0, 1, "", 2,
                shownRow.accentDepth()), value -> playRow(row, r -> r.withAccentDepth((float) value)))));
        // turned all the way up it lets the drum ring as it was recorded, which is where it starts
        shape.add(new Slot("decay", () -> knob(StudioPanels.boxExponentialKnob("Decay", 20, LONGEST_DECAY_MILLIS,
                "ms", 0, decay > 0 ? decay * 1000 : LONGEST_DECAY_MILLIS), value -> playRow(row, r -> r.withDecay(
                value >= LONGEST_DECAY_MILLIS - 0.5 ? 0 : value / 1000)))));
        shape.add(new Slot("attack", () -> knob(StudioPanels.boxExponentialKnob("Attack", 1, 2000, "ms", 0,
                channel.params().filterAttackSeconds() * 1000), value -> effects(channel, e -> e.sweeping(
                e.filterEnvAmountHz(), (float) (value / 1000), e.filterDecaySeconds(), e.filterSustainLevel(),
                e.filterReleaseSeconds())))));
        shape.addAll(details(channel::params, change -> effects(channel, change)));
        return new Pages(sound, shape);
    }

    private Pages loopPages(int track) {
        StudioTrack.Loop loop = loopAt(track);
        LiveEffects channel = loop.channel();
        List<String> cuts = List.copyOf(CUTS.keySet());
        int cut = Math.max(0, cuts.indexOf(cutName(loop.slicing().beats())));
        List<Slot> sound = new ArrayList<>();
        sound.add(new Slot("slice", () -> knob(StudioPanels.boxStepKnob("Slice", cuts, cut),
                value -> recut(track, CUTS.get(cuts.get((int) Math.round(value)))))));
        sound.addAll(filter(channel));
        sound.add(drive(channel));
        sound.addAll(sends(channel::params, change -> effects(channel, change)));
        List<Slot> shape = new ArrayList<>();
        shape.add(new Slot("level", () -> knob(StudioPanels.boxKnob("Level", 0, 1, "", 2, loop.gain()),
                value -> session.setGain(track, (float) value))));
        // how long the filter's sweep takes to fall back after each slice strikes it
        shape.add(new Slot("f.decay", () -> knob(StudioPanels.boxExponentialKnob("F.decay", 10, 3000, "ms", 0,
                channel.params().filterDecaySeconds() * 1000), value -> effects(channel, e -> e.sweeping(
                e.filterEnvAmountHz(), e.filterAttackSeconds(), (float) (value / 1000), e.filterSustainLevel(),
                e.filterReleaseSeconds())))));
        shape.add(null);
        shape.add(null);
        shape.addAll(details(channel::params, change -> effects(channel, change)));
        return new Pages(sound, shape);
    }

    private Pages melodyPages(int track) {
        StudioTrack.Melody melody = (StudioTrack.Melody) session.tracks().get(track);
        Instrument instrument = melody.instrument();
        if (instrument == null || instrument.setting() == null) {
            return null;
        }
        SynthParams params = instrument.setting().params();
        Supplier<EffectParams> effects = () -> instrument.setting().effects();
        Consumer<UnaryOperator<EffectParams>> change = turned -> sends(instrument, turned);
        List<String> waves = Arrays.stream(Waveform.values()).map(Waveform::toString).toList();
        List<Slot> sound = new ArrayList<>();
        sound.add(new Slot("wave", () -> knob(StudioPanels.boxStepKnob("Wave", waves, params.waveform().ordinal()),
                value -> synth(instrument, p -> p.withWaveform(Waveform.values()[(int) Math.round(value)])))));
        sound.add(new Slot("cutoff", () -> knob(StudioPanels.boxExponentialKnob("Cutoff", 40, 12000, "Hz", 0,
                params.cutoffHz()), value -> synth(instrument, p -> p.withCutoff((float) value)))));
        sound.add(new Slot("res", () -> knob(StudioPanels.boxKnob("Res", 0, 0.95, "", 2, params.resonance()),
                value -> synth(instrument, p -> p.withResonance((float) value)))));
        sound.add(new Slot("env", () -> knob(StudioPanels.boxBipolarKnob("Env", -4000, 8000, "Hz", 0,
                params.filterEnvAmountHz()), value -> synth(instrument, p -> p.withFilterEnvAmount((float) value)))));
        // a synth is played by its oscillators and its level's envelope as much as by its filter,
        // so all of them are on the first page and its effects go under shift
        sound.add(new Slot("voices", () -> knob(StudioPanels.boxKnob("Voices", 1, 7, "", 0, params.unisonVoices()),
                value -> synth(instrument, p -> p.withVoices((int) Math.round(value))))));
        sound.add(new Slot("detune", () -> knob(StudioPanels.boxKnob("Detune", 0, 60, "ct", 1, params.detuneCents()),
                value -> synth(instrument, p -> p.withDetune((float) value)))));
        sound.add(new Slot("sustain", () -> knob(StudioPanels.boxKnob("Sustain", 0, 1, "", 2, params.sustainLevel()),
                value -> synth(instrument, p -> p.withSustain((float) value)))));
        sound.add(new Slot("release", () -> knob(StudioPanels.boxExponentialKnob("Release", 1, 4000, "ms", 0,
                params.releaseSeconds() * 1000),
                value -> synth(instrument, p -> p.withRelease((float) (value / 1000))))));
        List<Slot> shape = new ArrayList<>();
        shape.add(new Slot("drive", () -> knob(StudioPanels.boxKnob("Drive", 0, 2, "", 2, params.drive()),
                value -> synth(instrument, p -> p.withDrive((float) value)))));
        shape.addAll(sends(effects, change));
        shape.addAll(details(effects, change));
        return new Pages(sound, shape);
    }

    /** A channel's filter, as a drum row and a loop share it: all the way open is no filter at all. */
    private static List<Slot> filter(LiveEffects channel) {
        EffectParams now = channel.params();
        return List.of(
                new Slot("cutoff", () -> knob(StudioPanels.boxExponentialKnob("Cutoff", 40, OPEN_CUTOFF, "Hz", 0,
                        now.filter() == ChannelFilter.OFF ? OPEN_CUTOFF : now.filterCutoffHz()),
                        value -> effects(channel, e -> e.filtered(
                                value >= OPEN_CUTOFF - 1 ? ChannelFilter.OFF : ChannelFilter.LADDER,
                                (float) value, e.filterResonance())))),
                new Slot("res", () -> knob(StudioPanels.boxKnob("Res", 0, 0.95, "", 2, now.filterResonance()),
                        value -> effects(channel, e -> e.filtered(e.filter(), e.filterCutoffHz(), (float) value)))),
                // the filter swept by an envelope struck at every hit or slice
                new Slot("env", () -> knob(StudioPanels.boxBipolarKnob("Env", -8000, 8000, "Hz", 0,
                        now.filterEnvAmountHz()), value -> effects(channel, e -> e.sweeping((float) value,
                        e.filterAttackSeconds(), e.filterDecaySeconds(), e.filterSustainLevel(),
                        e.filterReleaseSeconds())))));
    }

    private static Slot drive(LiveEffects channel) {
        return new Slot("drive", () -> knob(StudioPanels.boxKnob("Drive", 0, 2, "", 2, channel.params().drive()),
                value -> effects(channel, e -> e.driven(e.shape(), (float) value, e.outputTrim()))));
    }

    /** Delay, reverb and crush, wherever the effects live: a channel's own, or a synth's. */
    private static List<Slot> sends(Supplier<EffectParams> now, Consumer<UnaryOperator<EffectParams>> change) {
        return List.of(
                new Slot("delay", () -> knob(StudioPanels.boxKnob("Delay", 0, 1, "", 2, now.get().delayMix()),
                        value -> change.accept(e -> e.withDelayMix((float) value)))),
                new Slot("verb", () -> knob(StudioPanels.boxKnob("Verb", 0, 1, "", 2, now.get().reverbMix()),
                        value -> change.accept(e -> e.withReverbMix((float) value)))),
                new Slot("crush", () -> knob(StudioPanels.boxKnob("Crush", 0, 1, "", 2, now.get().crush()),
                        value -> change.accept(e -> e.withCrush((float) value)))));
    }

    /** The shape page's bottom row, the same for every part: the echo's time and feedback, the room, the duck. */
    private static List<Slot> details(Supplier<EffectParams> now, Consumer<UnaryOperator<EffectParams>> change) {
        return List.of(
                new Slot("time", () -> knob(StudioPanels.boxExponentialKnob("Time", 20, 2000, "ms", 0,
                        now.get().delayMillis()), value -> change.accept(e -> e.withDelayMillis((float) value)))),
                new Slot("feedback", () -> knob(StudioPanels.boxKnob("Feedback", 0, 0.95, "", 2,
                        now.get().delayFeedback()), value -> change.accept(e -> e.withDelayFeedback((float) value)))),
                new Slot("size", () -> knob(StudioPanels.boxKnob("Size", 0, 1, "", 2, now.get().reverbSize()),
                        value -> change.accept(e -> e.withReverbSize((float) value)))),
                // ducked under the kick, as the studio's sidechain does
                new Slot("duck", () -> knob(StudioPanels.boxKnob("Duck", 0, 1, "", 2, now.get().duckDepth()),
                        value -> change.accept(e -> e.withDuck((float) value, e.duckMillis())))));
    }

    private static PanelKnob knob(PanelKnob knob, DoubleConsumer onTurn) {
        knob.setOnChange(onTurn);
        return knob;
    }

    /** A knob of the selected melody's synth, heard in the notes already sounding. */
    private static void synth(Instrument instrument, UnaryOperator<SynthParams> change) {
        SynthSetting now = instrument.setting();
        if (now != null) {
            instrument.set(new SynthSetting(now.preset(), change.apply(now.params()), now.effects(), now.delaySync()));
        }
    }

    /** A drum row changed by a knob, and heard at once rather than from the next loop. */
    private void playRow(int row, UnaryOperator<GridRow> change) {
        session.changeRow(row, change);
        playback.takeNow();
    }

    /**
     * A knob of a drum row's or a loop's channel: set on the channel the engine is already
     * reading, so it is heard in the hits under way, with nothing to hand over.
     */
    private static void effects(LiveEffects channel, UnaryOperator<EffectParams> change) {
        channel.setParams(change.apply(channel.params()));
    }

    /** A knob of a melody's synth effects. */
    private static void sends(Instrument instrument, UnaryOperator<EffectParams> change) {
        SynthSetting now = instrument.setting();
        if (now != null) {
            instrument.set(new SynthSetting(now.preset(), now.params(), change.apply(now.effects()), now.delaySync()));
        }
    }

    // ---- the box

    /** A kit chosen from disk, with its file assignments shown after it has loaded successfully. */
    private void chooseDrumKit(Node owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Load drum samples folder");
        if (drumFolder != null && Files.isDirectory(drumFolder)) chooser.setInitialDirectory(drumFolder.toFile());
        File chosen = chooser.showDialog(owner.getScene().getWindow());
        if (chosen == null) return;
        try {
            SampleBank kit = playback.loadDrumKit(chosen.toPath());
            drumFolder = chosen.toPath();
            session.setDrumSamplesFolder(drumFolder);
            StringBuilder summary = new StringBuilder();
            for (Drum drum : Drum.values()) {
                Path source = kit.sourceFiles().get(drum);
                summary.append(drum.name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(": ")
                        .append(source == null ? "synthesized" : source.getFileName()).append('\n');
            }
            Alert report = new Alert(Alert.AlertType.INFORMATION);
            report.initOwner(owner.getScene().getWindow());
            report.setTitle("Drum samples loaded");
            report.setHeaderText(chosen.getName());
            report.setContentText(summary.toString());
            report.showAndWait();
        } catch (Exception failure) {
            showError(failure);
        }
    }

    /** The same file actions as the track strip, with a fresh workspace and the optional demo. */
    private MenuBar menuBar() {
        MenuBar bar = new MenuBar();
        bar.setStyle("-fx-background-color: transparent;");
        Menu file = new Menu("_File");
        MenuItem fresh = new MenuItem("New session");
        fresh.setAccelerator(KeyCombination.keyCombination("Shortcut+N"));
        fresh.setOnAction(event -> replaceJam(session::startEmpty));
        MenuItem open = new MenuItem("Open session...");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(event -> chooseProject(bar));
        MenuItem save = new MenuItem("Save session");
        save.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        save.setOnAction(event -> saveProject(bar, false));
        MenuItem saveAs = new MenuItem("Save session as...");
        saveAs.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+S"));
        saveAs.setOnAction(event -> saveProject(bar, true));
        MenuItem midi = new MenuItem("Import MIDI...");
        midi.setAccelerator(KeyCombination.keyCombination("Shortcut+I"));
        midi.setOnAction(event -> chooseMidi(bar));
        MenuItem audio = new MenuItem("Import audio loop...");
        audio.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+I"));
        audio.setOnAction(event -> chooseLoop(bar));
        MenuItem drums = new MenuItem("Load drum samples folder...");
        drums.setOnAction(event -> chooseDrumKit(bar));
        MenuItem demo = new MenuItem("Load demo");
        demo.setOnAction(event -> replaceJam(() -> DemoJam.startIn(session)));
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(event -> Platform.exit());
        file.getItems().addAll(fresh, open, save, saveAs, new SeparatorMenuItem(), midi, audio, drums,
                new SeparatorMenuItem(), demo, new SeparatorMenuItem(), exit);
        Menu transport = new Menu("_Playback");
        MenuItem toggle = new MenuItem("Play / Stop");
        toggle.setOnAction(event -> togglePlayback());
        transport.getItems().add(toggle);
        bar.getMenus().addAll(file, transport);
        return bar;
    }

    /** A different jam starts stopped, with the first drum row visible and its tempo on the strip. */
    private void replaceJam(Runnable load) {
        playback.stop();
        load.run();
        projectFile = null;
        ((Stage)trackScroll.getScene().getWindow()).setTitle("Groovebox");
        refreshJamView();
    }

    private void refreshJamView() {
        drumRow = 0;
        viewPage = 0;
        chosenSlice = -1;
        tempo.show(session.bpm());
        showTransport();
        int track = session.tracks().selectedIndex();
        select(switch (session.tracks().selected()) {
            case StudioTrack.Drums drums -> new Part.DrumRow(0);
            case StudioTrack.Melody melody -> new Part.Melody(track);
            case StudioTrack.Loop loop -> new Part.Loop(track);
        });
        trackScroll.setHvalue(0);
    }

    private FileChooser projectChooser() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Groovebox sessions", "*.properties"));
        if (projectFile != null && Files.isDirectory(projectFile.getParent())) {
            chooser.setInitialDirectory(projectFile.getParent().toFile());
            chooser.setInitialFileName(projectFile.getFileName().toString());
        } else chooser.setInitialFileName("jam.properties");
        return chooser;
    }

    private void chooseProject(Node owner) {
        FileChooser chooser = projectChooser();
        chooser.setTitle("Open session");
        File chosen = chooser.showOpenDialog(owner.getScene().getWindow());
        if (chosen != null) {
            try { openProject(chosen.toPath()); }
            catch (Exception failure) { showError(failure); }
        }
    }

    /** A failed read leaves the workspace playing; a successfully opened project starts stopped. */
    private void openProject(Path file) throws Exception {
        SampleBank kit = session.open(file);
        playback.stop();
        drumFolder = session.drumSamplesFolder();
        playback.useDrumKit(kit == null ? defaultDrumKit : kit);
        projectFile = file.toAbsolutePath().normalize();
        ((Stage)trackScroll.getScene().getWindow()).setTitle("Groovebox · " + projectFile.getFileName());
        refreshJamView();
    }

    private void saveProject(Node owner, boolean chooseFile) {
        Path target = projectFile;
        if (chooseFile || target == null) {
            FileChooser chooser = projectChooser();
            chooser.setTitle("Save session");
            File chosen = chooser.showSaveDialog(owner.getScene().getWindow());
            if (chosen == null) return;
            target = chosen.toPath();
            if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".properties")) {
                target = target.resolveSibling(target.getFileName() + ".properties");
            }
        }
        try {
            session.save(target);
            projectFile = target.toAbsolutePath().normalize();
            ((Stage)owner.getScene().getWindow()).setTitle("Groovebox · " + projectFile.getFileName());
        } catch (Exception failure) { showError(failure); }
    }

    /**
     * The box as its sketch has it, top to bottom: the screen with the knobs beside it, play and
     * the tempo with the performance strips, the pads, the tracks, and a line saying what it runs
     * on.
     */
    private StackPane layout() {
        Label name = new Label("groovebox");
        name.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: " + Look.web(Look.INK) + ";");
        shift.setFocusTraversable(false);
        shift.setOnAction(event -> toggleShift());
        Tooltip.install(shift, new Tooltip("The knobs' other page, printed in orange under them (Tab)"));
        showShift();
        Region beforeShift = new Region();
        HBox.setHgrow(beforeShift, Priority.ALWAYS);
        ToggleGroup views = new ToggleGroup();
        instrumentTab = new ToggleButton("Instrument"); instrumentTab.setId("view-instrument");
        mixerTab = new ToggleButton("Mixer"); mixerTab.setId("view-mixer");
        instrumentTab.setToggleGroup(views); mixerTab.setToggleGroup(views); instrumentTab.setSelected(true);
        styleViewTab(instrumentTab); styleViewTab(mixerTab);
        instrumentTab.setOnAction(event -> showMixer(false));
        mixerTab.setOnAction(event -> showMixer(true));
        HBox title = new HBox(12, name, menuBar(), instrumentTab, mixerTab, beforeShift, shift);
        title.setAlignment(Pos.CENTER_LEFT);

        knobs.setHgap(8);
        knobs.setVgap(10);
        knobs.setPrefWidth(KNOBS_WIDTH);
        knobs.setMinHeight(Screen.HEIGHT);
        knobs.setAlignment(Pos.TOP_CENTER);
        Node display = screen.node();
        display.setOnMouseMoved(event -> display.setCursor(selected instanceof Part.Loop
                || selected instanceof Part.DrumRow && Screen.drumRowAt(event.getY(), session.rows().size()) >= 0
                ? Cursor.HAND : Cursor.DEFAULT));
        display.setOnDragDetected(event -> {
            int slice = sliceOnScreen(event.getX());
            if (slice >= 0) {
                // carried to a pad, which plays it from then on
                Pads.startDrag(display, slice);
            }
            event.consume();
        });
        display.setOnMouseClicked(event -> {
            int slice = event.isStillSincePress() ? sliceOnScreen(event.getX()) : -1;
            if (slice >= 0) {
                // picked for the pads to play when pressed, until it is clicked again
                chosenSlice = chosenSlice == slice ? -1 : slice;
                shown = "";
            }
        });
        display.setOnMousePressed(event -> {
            if (selected instanceof Part.Loop(int track)) {
                // heard as soon as it is touched, whether it is then picked or carried
                int slice = sliceOnScreen(event.getX());
                if (slice >= 0) {
                    trySlice(loopAt(track), slice);
                }
                return;
            }
            int row = selected instanceof Part.DrumRow ? Screen.drumRowAt(event.getY(), session.rows().size()) : -1;
            if (row >= 0) {
                select(new Part.DrumRow(row));
            }
        });
        HBox top = new HBox(GAP, display, knobs);

        play.setPrefSize(PLAY_WIDTH, Strip.HEIGHT);
        play.setMinSize(PLAY_WIDTH, Strip.HEIGHT);
        play.setFocusTraversable(false);
        Tooltip.install(play, new Tooltip("Play and stop (Space)"));
        play.setOnAction(event -> togglePlayback());
        showTransport();
        tempo.show(session.bpm());
        globalEffects = performance(); globalEffects.setId("global-effects");
        globalEffects.setVisible(false);
        transport = new HBox(STRIP_GAP + 6, play, tempo.node());
        transport.setId("transport"); play.setId("play-stop");
        transport.setAlignment(Pos.CENTER_LEFT);

        instrumentView = new VBox(16, top, transport, pads.node(), trackBar());
        instrumentView.setId("instrument-view");
        mixer = new MixerView(session, track -> select(switch (session.tracks().get(track)) {
            case StudioTrack.Drums ignored -> new Part.DrumRow(drumRow);
            case StudioTrack.Melody ignored -> new Part.Melody(track);
            case StudioTrack.Loop ignored -> new Part.Loop(track);
        }), () -> showMixer(false), track -> chooseMidiWindow(mixer.node(), track), () -> {
            showChips(); shown = "";
        });
        mixerPane = new VBox(8, mixer.node(), globalEffects);
        mixerPane.setVisible(false); mixerPane.setManaged(false);
        StackPane viewsPane = new StackPane(instrumentView, mixerPane);
        viewsPane.setAlignment(Pos.TOP_LEFT);
        // Reserve the original instrument's height, so switching never resizes the box.
        viewsPane.setMinHeight(396); viewsPane.setPrefHeight(396); viewsPane.setMaxHeight(396);

        status.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px; -fx-text-fill: "
                + Look.web(Look.QUIET_INK) + ";");
        Label made = new Label("jdk " + Runtime.version().feature() + " · javafx · java sound");
        made.setStyle(status.getStyle());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox footer = new HBox(made, gap, status);

        VBox body = new VBox(16, title, viewsPane, footer);
        VBox.setMargin(footer, new Insets(2, 0, 0, 0));
        body.setPadding(new Insets(20, 24, 16, 24));
        body.setPrefWidth(INSIDE + 48);
        body.setMaxWidth(INSIDE + 48);
        body.setStyle("-fx-background-color: " + Look.web(Look.BODY_EDGE) + ", " + Look.web(Look.BODY)
                + "; -fx-background-insets: 0, 1; -fx-background-radius: 22, 21;");
        StackPane room = new StackPane(body);
        room.setPadding(new Insets(20));
        room.setStyle("-fx-background-color: " + Look.web(Look.ROOM) + ";");
        return room;
    }

    private void showMixer(boolean active) {
        mixing = active;
        globalEffects.setVisible(active);
        instrumentView.getChildren().remove(transport);
        mixerPane.getChildren().remove(transport);
        transport.getChildren().remove(mixer.inspector());
        if (active) transport.getChildren().add(mixer.inspector());
        if (active) mixerPane.getChildren().add(1, transport);
        else instrumentView.getChildren().add(1, transport);
        instrumentView.setVisible(!active); instrumentView.setManaged(!active);
        mixerPane.setVisible(active); mixerPane.setManaged(active);
        instrumentTab.setSelected(!active); mixerTab.setSelected(active);
        styleViewTab(instrumentTab); styleViewTab(mixerTab);
        shift.setVisible(!active); shift.setManaged(!active);
        if (active) mixer.refresh(playback.live() == null ? MixLevels.SILENT : playback.live().levels());
    }

    private static void styleViewTab(ToggleButton tab) {
        tab.setFocusTraversable(false);
        tab.setStyle("-fx-background-color: " + (tab.isSelected() ? Look.CHIP_ON : Look.CHIP_OFF)
                + "; -fx-text-fill: " + (tab.isSelected() ? Look.CHIP_ON_TEXT : Look.CHIP_OFF_TEXT)
                + "; -fx-background-radius: 14; -fx-padding: 3 14; -fx-font-size: 13px; -fx-cursor: hand;");
    }

    /** The effects played over the whole mix, with explicit locks and one release for all cards. */
    private Node performance() {
        double width = (INSIDE - 24 - 3 * STRIP_GAP) / 4;
        double[] slices = {0.125, 0.25, 0.5, 1.0};
        strips.add(Strip.zones("Stutter", List.of("1/32", "1/16", "1/8", "1/4"), width, zone -> {
            var live = playback.live();
            if (live != null) {
                live.stutter(zone < 0 ? 0 : slices[(int) zone]);
            }
        }));
        // low-pass to the left, high-pass to the right, nothing done at the middle
        strips.add(Strip.continuous("Filter", width, value -> {
            var live = playback.live();
            if (live != null) {
                live.performance().filter(value);
            }
        }));
        strips.add(Strip.continuous("Dub", width, value -> {
            var live = playback.live();
            if (live != null) {
                live.performance().dub(value);
            }
        }));
        strips.add(Strip.continuous("Talkbox", width, value -> {
            var live = playback.live();
            if (live != null) {
                live.performance().talkbox(value);
            }
        }));
        HBox row = new HBox(STRIP_GAP);
        strips.forEach(strip -> row.getChildren().add(strip.node()));
        Label title = new Label("Master FX");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + Look.web(Look.INK) + ";");
        Label hint = new Label("All tracks · hold to perform · Lock to keep");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Look.web(Look.QUIET_INK) + ";");
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        Button release = new Button("Release FX"); release.setId("release-fx");
        release.setStyle("-fx-font-size: 11px; -fx-padding: 4 9;");
        release.setOnAction(event -> strips.forEach(Strip::release));
        HBox header = new HBox(8, title, hint, gap, release); header.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(8, header, row); panel.setPadding(new Insets(8, 12, 8, 12));
        panel.setPrefHeight(122); panel.setMinHeight(122); panel.setMaxHeight(122);
        panel.setStyle("-fx-background-color: #e4e1d7; -fx-border-color: #c5c2b5; -fx-border-radius: 11; -fx-background-radius: 11;");
        panel.getStylesheets().add(Groovebox.class.getResource("master-fx.css").toExternalForm());
        return panel;
    }

    private static void showError(Throwable failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(failure.getMessage()));
        alert.setHeaderText(failure.getClass().getSimpleName());
        alert.show();
    }
}
