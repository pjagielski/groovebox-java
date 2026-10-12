package io.groovebox.app;

import io.groovebox.model.Note;
import io.groovebox.session.Session;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.List;
import java.util.function.BiConsumer;

/** A file overview and a piano roll of the chosen bars, drawn from the same notes the preview sounds. */
final class MidiRangePreview {
    private static final double WIDTH = 620;
    private static final double LEFT = 48;
    private static final double RIGHT = WIDTH - 12;
    private static final double TOP = 24;
    private static final double BOTTOM = 140;
    private final Canvas overview = new Canvas(WIDTH, 58);
    private final Canvas roll = new Canvas(WIDTH, 166);
    private final Label caption = new Label();
    private final VBox node = new VBox(6, caption, overview, roll);
    private List<Note> phrase = List.of();
    private int fileBars = 1;
    private int from = 1;
    private int through = 1;
    private double firstBeat;
    private double lastBeat = 4;
    private int low = 48;
    private int high = 60;
    private int anchor;
    private boolean valid;

    MidiRangePreview(BiConsumer<Integer, Integer> choose) {
        overview.setId("midi-overview");
        roll.setId("midi-roll");
        Tooltip.install(overview, new Tooltip("Drag across the file overview to select a range of bars"));
        overview.setOnMousePressed(event -> {
            if (valid && event.getButton() == MouseButton.PRIMARY) {
                anchor = barAt(event.getX());
                choose.accept(anchor, anchor);
            }
        });
        overview.setOnMouseDragged(event -> {
            if (valid && event.isPrimaryButtonDown()) {
                int bar = barAt(event.getX());
                choose.accept(Math.min(anchor, bar), Math.max(anchor, bar));
            }
        });
    }

    Node node() {
        return node;
    }

    List<Note> notes() {
        return phrase;
    }

    double rangeBeats() {
        return lastBeat - firstBeat;
    }

    void show(List<Note> line, List<Note> selected, int totalBars, double first, double last) {
        phrase = selected;
        fileBars = totalBars;
        firstBeat = first;
        lastBeat = last;
        from = (int) Math.floor(first / Session.BEATS_PER_BAR) + 1;
        through = (int) Math.ceil(last / Session.BEATS_PER_BAR);
        valid = true;
        low = line.stream().mapToInt(Steps::pitchOf).filter(pitch -> pitch >= 0).min().orElse(48);
        high = line.stream().mapToInt(Steps::pitchOf).filter(pitch -> pitch >= 0).max().orElse(60);
        while (high - low < 12) {
            if (low > 0) low--;
            if (high < 127 && high - low < 12) high++;
        }
        String title = "Bars " + from + "–" + through + " · beats " + decimal(first) + "–" + decimal(last)
                + " · " + selected.size() + " notes"
                + (selected.isEmpty() ? " · silent range" : " · "
                + Steps.nameOf(selected.stream().mapToInt(Steps::pitchOf).min().orElse(low)) + "–"
                + Steps.nameOf(selected.stream().mapToInt(Steps::pitchOf).max().orElse(high)));
        caption.setText(title);
        overview.setAccessibleText("File overview, " + totalBars + " bars. " + title);
        roll.setAccessibleText("Piano roll. " + title);
        drawOverview(line);
        drawRoll(-1);
    }

    void clear() {
        valid = false;
        phrase = List.of();
        caption.setText("Enter a valid range to preview its notes");
        overview.setAccessibleText("No valid MIDI range selected");
        roll.setAccessibleText("No valid MIDI range selected");
        background(overview);
        background(roll);
    }

    void playhead(double beat) {
        if (valid) drawRoll(beat);
    }

    private int barAt(double x) {
        return Math.max(1, Math.min(fileBars, (int) Math.floor((x - LEFT) / (RIGHT - LEFT) * fileBars) + 1));
    }

    private void drawOverview(List<Note> line) {
        GraphicsContext g = background(overview);
        double width = (RIGHT - LEFT) / fileBars;
        double start = LEFT + firstBeat / Session.BEATS_PER_BAR * width;
        double selectedWidth = rangeBeats() / Session.BEATS_PER_BAR * width;
        g.setFill(Look.CELL_NOW);
        g.fillRect(start, 8, selectedWidth, 36);
        for (Note note : line) {
            int pitch = Steps.pitchOf(note);
            if (pitch < 0) continue;
            double x = LEFT + note.beat() / Session.BEATS_PER_BAR * width;
            double y = 36 - (pitch - low) / (double) (high - low + 1) * 26;
            g.setFill(note.beat() >= firstBeat && note.beat() < lastBeat ? Look.CELL_ON : Look.SCREEN_DIM);
            g.fillRect(x, y, Math.max(1, note.durationBeats() / Session.BEATS_PER_BAR * width), 3);
        }
        g.setStroke(Look.PLAYHEAD);
        g.strokeRect(start, 7, selectedWidth, 38);
        g.setFill(Look.SCREEN_DIM);
        g.fillText("file", 9, 27);
        int step = Math.max(1, (int) Math.ceil(fileBars / 12.0));
        for (int bar = 0; bar < fileBars; bar += step) g.fillText(String.valueOf(bar + 1), LEFT + bar * width, 53);
    }

    private void drawRoll(double beat) {
        GraphicsContext g = background(roll);
        int span = high - low + 1;
        double rowHeight = (BOTTOM - TOP) / span;
        double rangeBeats = rangeBeats();
        for (int pitch = low; pitch <= high; pitch++) {
            double y = BOTTOM - (pitch - low + 1) * rowHeight;
            g.setFill((pitch - low) % 2 == 0 ? Look.SCREEN_ROW : Look.SCREEN_ROW_OTHER);
            g.fillRect(LEFT, y, RIGHT - LEFT, rowHeight);
            if (pitch == low || pitch == high || (pitch % 12 == 0 && rowHeight > 5)) {
                g.setFill(Look.SCREEN_DIM);
                g.fillText(Steps.nameOf(pitch), 8, y + Math.max(8, rowHeight / 2));
            }
        }
        int count = through - from + 1;
        int step = Math.max(1, (int) Math.ceil(count / 12.0));
        for (int bar = 0; bar < count; bar++) {
            double position = (from - 1 + bar) * (double) Session.BEATS_PER_BAR;
            double x = LEFT + (position - firstBeat) / rangeBeats * (RIGHT - LEFT);
            if (x < LEFT || x > RIGHT) continue;
            g.setStroke(Look.CELL_OFF);
            g.strokeLine(x, TOP, x, BOTTOM);
            if (bar % step == 0) {
                g.setFill(Look.SCREEN_DIM);
                g.fillText(String.valueOf(from + bar), x + 2, 158);
            }
        }
        for (Note note : phrase) {
            int pitch = Steps.pitchOf(note);
            if (pitch < 0) continue;
            double x = LEFT + note.beat() / rangeBeats * (RIGHT - LEFT);
            double width = note.durationBeats() / rangeBeats * (RIGHT - LEFT);
            double y = BOTTOM - (pitch - low + 1) * rowHeight;
            g.setFill(beat >= note.beat() && beat < note.beat() + note.durationBeats() ? Look.SCREEN_TEXT : Look.CELL_ON);
            g.fillRoundRect(x + 0.5, y + 1, Math.max(1.5, width - 1), Math.max(2, rowHeight - 2), 2, 2);
        }
        if (phrase.isEmpty()) {
            g.setFill(Look.SCREEN_TEXT);
            g.fillText("No notes in this range", LEFT + 12, 96);
        }
        if (beat >= 0 && beat < rangeBeats) {
            double x = LEFT + beat / rangeBeats * (RIGHT - LEFT);
            g.setStroke(Look.PLAYHEAD);
            g.setLineWidth(2);
            g.strokeLine(x, TOP, x, BOTTOM);
        }
    }

    private static GraphicsContext background(Canvas canvas) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setLineWidth(1);
        g.setFill(Look.SCREEN);
        g.fillRoundRect(0, 0, canvas.getWidth(), canvas.getHeight(), 8, 8);
        g.setFont(Font.font("Consolas", 11));
        return g;
    }

    private static String decimal(double number) {
        return java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }
}
