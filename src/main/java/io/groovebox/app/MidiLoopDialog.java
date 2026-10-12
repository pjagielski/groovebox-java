package io.groovebox.app;

import io.groovebox.session.MidiPart;
import io.groovebox.session.MidiWindow;
import io.groovebox.midi.MidiFileReader;
import io.groovebox.model.Note;
import io.groovebox.synth.PitchSynth;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.math.BigDecimal;
import javax.sound.midi.Sequence;

/** The file part and inclusive bar range a track plays, alongside the length of the shared jam. */
final class MidiLoopDialog {
    record Edit(MidiWindow window, double jamBars) {
    }

    private MidiLoopDialog() {
    }

    /** Uses the same preview and audition when choosing a part before it is added to the jam. */
    static Dialog<Edit> createImport(Window owner, MidiWindow window, List<MidiPart> parts, double jamBars,
                                     Sequence sequence, double bpm, PitchSynth synth, Runnable beforeListen) {
        Dialog<Edit> dialog = create(owner, window, parts, jamBars, sequence, bpm, synth, beforeListen);
        dialog.setTitle("Add MIDI track");
        ButtonType apply = dialog.getDialogPane().getButtonTypes().getFirst();
        ((Button) dialog.getDialogPane().lookupButton(apply)).setText("Add track");
        return dialog;
    }

    static Dialog<Edit> create(Window owner, MidiWindow window, List<MidiPart> parts, double jamBars,
                               Sequence sequence, double bpm, PitchSynth synth, Runnable beforeListen) {
        Dialog<Edit> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("MIDI loop");
        dialog.setHeaderText(window.file().getFileName().toString());
        ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        dialog.getDialogPane().setStyle("-fx-background-color: " + Look.web(Look.BODY) + ";");

        int fileBars = Math.max(window.startBar() + 1, parts.stream().mapToInt(MidiPart::endBar).max().orElse(1));
        ComboBox<MidiPart> source = new ComboBox<>();
        source.setId("midi-source");
        source.getItems().setAll(parts);
        source.setMaxWidth(Double.MAX_VALUE);
        source.setConverter(new StringConverter<>() {
            @Override public String toString(MidiPart part) {
                return part == null ? "" : "Track " + part.trackIndex() + " · " + part.name();
            }
            @Override public MidiPart fromString(String text) { return null; }
        });
        source.setValue(parts.stream().filter(part -> part.trackIndex() == window.trackIndex()).findFirst()
                .orElse(parts.getFirst()));
        Spinner<Integer> from = new Spinner<>(1, fileBars, window.startBar() + 1);
        from.setId("midi-from");
        int end = window.endBar() == 0 ? Math.min(fileBars, window.startBar() + (int) Math.ceil(jamBars)) : window.endBar();
        Spinner<Integer> through = new Spinner<>(1, fileBars, Math.max(window.startBar() + 1, end));
        through.setId("midi-through");
        Spinner<Double> startBeat = new Spinner<>(0.0, fileBars * 4.0, window.startBeat(), 0.25);
        startBeat.setId("midi-start-beat");
        Spinner<Double> endBeat = new Spinner<>(0.25, fileBars * 4.0,
                window.endBeat() == 0 ? Math.min(fileBars * 4.0, window.startBeat() + jamBars * 4) : window.endBeat(), 0.25);
        endBeat.setId("midi-end-beat");
        Spinner<Double> length = new Spinner<>(0.25, Math.max(128, fileBars), jamBars, 0.25);
        length.setId("midi-jam");
        from.setEditable(true);
        through.setEditable(true);
        length.setEditable(true);
        startBeat.setEditable(true);
        endBeat.setEditable(true);
        for (Spinner<Double> value : List.of(startBeat, endBeat, length)) {
            value.getValueFactory().setConverter(new StringConverter<>() {
                @Override public String toString(Double number) { return decimal(number); }
                @Override public Double fromString(String text) { return number(text); }
            });
        }
        CheckBox repeat = new CheckBox("Repeat this range within the jam");
        repeat.setId("midi-repeat");
        repeat.setSelected(window.looping());

        GridPane fields = new GridPane();
        fields.setHgap(18);
        fields.setVgap(8);
        Label sourceLabel = new Label("File track");
        sourceLabel.setLabelFor(source);
        Label fromLabel = new Label("From bar");
        fromLabel.setLabelFor(from);
        Label throughLabel = new Label("Through bar");
        throughLabel.setLabelFor(through);
        Label lengthLabel = new Label("Jam length (bars)");
        lengthLabel.setLabelFor(length);
        fields.addRow(0, sourceLabel, source);
        Label startLabel = new Label("Start beat");
        startLabel.setLabelFor(startBeat);
        Label endLabel = new Label("End beat");
        endLabel.setLabelFor(endBeat);
        from.setPrefWidth(110);
        through.setPrefWidth(110);
        startBeat.setPrefWidth(130);
        endBeat.setPrefWidth(130);
        fields.addRow(1, fromLabel, new HBox(12, from, startLabel, startBeat));
        fields.addRow(2, throughLabel, new HBox(12, through, endLabel, endBeat));
        fields.addRow(3, lengthLabel, length);
        GridPane.setHgrow(source, Priority.ALWAYS);
        Label occupied = new Label();
        Button firstFour = new Button("Use first 4 bars with notes");
        firstFour.setOnAction(event -> {
            MidiPart part = source.getValue();
            from.getValueFactory().setValue(part.firstBar() + 1);
            through.getValueFactory().setValue(Math.min(fileBars, part.firstBar() + 4));
        });
        Label help = new Label("Beats count from 0: start 3.75 is just before bar 2. End beat is exclusive.\n"
                + "Whole-bar controls include both ends. Jam length affects all tracks.\n"
                + "Drag on the file overview to select bars. Listen plays the range once with this track's sound.");
        help.setWrapText(true);
        Label feedback = new Label();
        feedback.setWrapText(true);
        feedback.setMinHeight(26);
        feedback.setStyle("-fx-text-fill: " + Look.web(Look.INK) + ";");
        MidiRangePreview preview = new MidiRangePreview((start, stop) -> {
            from.getValueFactory().setValue(start);
            through.getValueFactory().setValue(stop);
        });
        Map<Integer, List<Note>> lines = new HashMap<>();
        MidiAudition audition = new MidiAudition();
        Button listen = new Button("Listen range");
        listen.setId("midi-listen");
        Label sound = new Label("Jam pauses during audition");
        var clock = new AnimationTimer() {
            void finish() {
                stop();
                audition.close();
                preview.playhead(-1);
                listen.setText("Listen range");
            }

            @Override public void handle(long now) {
                Throwable failure = audition.failure();
                double beat = audition.beat();
                if (failure != null || beat >= preview.rangeBeats()) {
                    finish();
                    if (failure != null) feedback.setText("Audition failed: " + failure.getMessage());
                } else {
                    preview.playhead(beat);
                }
            }
        };
        listen.setOnAction(event -> {
            if (audition.playing()) {
                clock.finish();
                return;
            }
            try {
                beforeListen.run();
                audition.start(preview.notes(), bpm, preview.rangeBeats(), synth);
                listen.setText("Stop preview");
                clock.start();
            } catch (Exception failure) {
                clock.finish();
                feedback.setText("Cannot audition: " + failure.getMessage());
            }
        });
        dialog.setOnHidden(event -> clock.finish());
        VBox content = new VBox(8, fields, new HBox(14, firstFour, occupied), preview.node(),
                new HBox(14, listen, sound), repeat, help, feedback);
        content.setPadding(new Insets(8));
        content.setPrefWidth(636);
        dialog.getDialogPane().setContent(content);

        Runnable validate = () -> {
            clock.finish();
            MidiPart part = source.getValue();
            occupied.setText(part == null ? "Choose a file track" : part.noteCount() + " notes · active in bars "
                    + (part.firstBar() + 1) + "–" + part.endBar());
            try {
                int start = Integer.parseInt(from.getEditor().getText().trim());
                int stop = Integer.parseInt(through.getEditor().getText().trim());
                double firstBeat = number(startBeat.getEditor().getText());
                double lastBeat = number(endBeat.getEditor().getText());
                double bars = number(length.getEditor().getText());
                if (part == null || start < 1 || stop < start || stop > fileBars || !Double.isFinite(firstBeat)
                        || !Double.isFinite(lastBeat) || firstBeat < 0 || lastBeat <= firstBeat || lastBeat > fileBars * 4.0) {
                    throw new IllegalArgumentException("Choose a range from bar 1 to " + fileBars + ", with the end at or after the start.");
                }
                if (!Double.isFinite(bars) || bars < 0.25 || bars > Math.max(128, fileBars)) {
                    throw new IllegalArgumentException("Jam length must be between 0.25 and " + Math.max(128, fileBars) + " bars.");
                }
                double range = (lastBeat - firstBeat) / 4;
                List<Note> line = lines.computeIfAbsent(part.trackIndex(), index -> MidiFileReader.readTrack(sequence, index));
                List<Note> phrase = new MidiWindow(window.file(), part.trackIndex(), firstBeat, lastBeat, false)
                        .notes(sequence, lastBeat - firstBeat);
                preview.show(line, phrase, fileBars, firstBeat, lastBeat);
                listen.setDisable(phrase.isEmpty());
                feedback.setText(range > bars ? "Only the first " + bars + " bars of this range fit. Increase jam length to hear it all."
                        : "Range: " + decimal(lastBeat - firstBeat) + " beats (" + decimal(range) + " bars)" + (repeat.isSelected()
                        ? " · repeats within each jam pass" : " · plays once per jam pass"));
                dialog.getDialogPane().lookupButton(apply).setDisable(false);
            } catch (NumberFormatException badNumber) {
                preview.clear();
                listen.setDisable(true);
                feedback.setText("Enter whole bar numbers and numeric beat positions or jam length (3.75 or 3,75).");
                dialog.getDialogPane().lookupButton(apply).setDisable(true);
            } catch (IllegalArgumentException invalid) {
                preview.clear();
                listen.setDisable(true);
                feedback.setText(invalid.getMessage());
                dialog.getDialogPane().lookupButton(apply).setDisable(true);
            }
        };
        linkPosition(from, startBeat, false, validate);
        linkPosition(through, endBeat, true, validate);
        length.getEditor().textProperty().addListener((obs, was, now) -> validate.run());
        source.valueProperty().addListener((obs, was, now) -> validate.run());
        repeat.selectedProperty().addListener((obs, was, now) -> validate.run());
        validate.run();
        dialog.setResultConverter(button -> button == apply ? new Edit(new MidiWindow(window.file(),
                source.getValue().trackIndex(), number(startBeat.getEditor().getText()),
                number(endBeat.getEditor().getText()), repeat.isSelected()),
                number(length.getEditor().getText())) : null);
        return dialog;
    }

    private static void linkPosition(Spinner<Integer> bar, Spinner<Double> beat, boolean end, Runnable validate) {
        boolean[] syncing = {false};
        bar.getEditor().textProperty().addListener((obs, was, now) -> {
            if (!syncing[0]) {
                syncing[0] = true;
                try {
                    double position = (Integer.parseInt(now.trim()) - (end ? 0 : 1)) * 4.0;
                    beat.getValueFactory().setValue(position);
                    beat.getEditor().setText(decimal(position));
                } catch (NumberFormatException incomplete) {
                    // Typing is allowed to pass through an incomplete number; Apply stays disabled.
                } finally {
                    syncing[0] = false;
                }
            }
            validate.run();
        });
        beat.getEditor().textProperty().addListener((obs, was, now) -> {
            if (!syncing[0]) {
                syncing[0] = true;
                try {
                    double position = number(now);
                    if (Double.isFinite(position) && position >= 0) {
                        int index = end ? (int) Math.ceil(position / 4) : (int) Math.floor(position / 4) + 1;
                        bar.getValueFactory().setValue(index);
                        bar.getEditor().setText(String.valueOf(index));
                    }
                } catch (NumberFormatException incomplete) {
                    // The validation message belongs to the draft, not to its last valid value.
                } finally {
                    syncing[0] = false;
                }
            }
            validate.run();
        });
    }

    private static double number(String text) {
        return Double.parseDouble(text.trim().replace(',', '.'));
    }

    private static String decimal(double number) {
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }
}
