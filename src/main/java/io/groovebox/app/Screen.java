package io.groovebox.app;

import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import io.groovebox.audio.Sample;
import io.groovebox.model.Note;
import io.groovebox.model.Scale;
import io.groovebox.session.GridRow;
import io.groovebox.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The box's screen: green on black, a bar at a time. For the drums it shows the whole kit, a row a
 * drum, with the selected one framed; for a melody, a piano roll of the bar in the jam's key.
 */
final class Screen {
    static final double WIDTH = 520;
    static final double HEIGHT = 200;
    private static final double LEFT = 70;
    // a loop has no row names to make room for, so its audio takes the screen from edge to edge
    private static final double LOOP_LEFT = 16;
    private static final double RIGHT = WIDTH - 16;
    private static final double TOP = 34;
    private static final double BOTTOM = HEIGHT - 26;
    private static final Font TEXT = Font.font("Consolas", 13);
    private static final Font SMALL = Font.font("Consolas", 11);

    private final Canvas canvas = new Canvas(WIDTH, HEIGHT);

    Node node() {
        return canvas;
    }

    void drawDrums(List<GridRow> rows, int selected, double beat) {
        GraphicsContext g = begin("drums");
        double cell = (RIGHT - LEFT) / Steps.PADS;
        double rowHeight = drumRowHeight(rows.size());
        List<String> names = names(rows);
        for (int index = 0; index < rows.size(); index++) {
            GridRow row = rows.get(index);
            double y = TOP + index * rowHeight;
            boolean chosen = index == selected;
            g.setFont(SMALL);
            g.setFill(chosen ? Look.SCREEN_TEXT : Look.SCREEN_DIM);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(names.get(index), 14, y + rowHeight / 2);
            int page = Steps.page(row, beat);
            int current = Steps.current(row, beat);
            for (int pad = 0; pad < Steps.PADS; pad++) {
                int step = page * Steps.PADS + pad;
                if (step >= row.accents().length) {
                    break;
                }
                float accent = row.accents()[step];
                boolean now = step == current;
                Color fill = accent > 0
                        ? (now ? Look.SCREEN_TEXT : accent < 0.6f ? Look.CELL_SOFT : Look.CELL_ON)
                        : (now ? Look.CELL_NOW : Look.CELL_OFF);
                g.setFill(fill);
                g.fillRoundRect(LEFT + pad * cell + 1, y + 2, cell - 3, rowHeight - 6, 3, 3);
            }
            if (chosen) {
                g.setStroke(Look.CELL_ON);
                g.setLineWidth(1);
                g.strokeRoundRect(LEFT - 2, y - 0.5, RIGHT - LEFT + 3, rowHeight - 1, 4, 4);
            }
        }
        beatNumbers(g, cell);
    }

    /**
     * A cut loop, a page of it at a time: the audio its sixteen steps cover, as recorded, in bands
     * where its slices begin, each numbered as the pads number them. The slice picked to place on
     * the pads is framed, the one sounding is lit, and the playhead runs over the recording.
     *
     * @param peaks the loudest of the audio in equal pieces of it, see {@link #peaks}
     * @param passFraction how far through its pass the loop is, 0 to 1; -1 while nothing plays
     */
    void drawLoop(String name, String cut, float[] peaks, int frameCount, List<Integer> starts,
                  List<Integer> order, int page, int current, int chosen, double passFraction) {
        int pages = Steps.pages(order.size());
        GraphicsContext g = begin(name.toLowerCase(Locale.ROOT) + " · " + cut);
        if (pages > 1) {
            g.setFont(SMALL);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(Look.SCREEN_DIM);
            g.fillText("page " + (page + 1) + "/" + pages, RIGHT, 16);
        }
        int from = pageStart(page, order.size(), frameCount);
        int until = pageStart(page + 1, order.size(), frameCount);
        int sounding = current >= 0 && current < order.size() ? order.get(current) : -1;
        double middle = (TOP + BOTTOM) / 2 - 6;
        double half = (BOTTOM - TOP) / 2 - 8;
        for (int slice = 0; slice < starts.size(); slice++) {
            int start = starts.get(slice);
            int end = slice + 1 < starts.size() ? starts.get(slice + 1) : frameCount;
            if (end <= from || start >= until) {
                continue;
            }
            double x0 = loopX(Math.max(start, from), from, until);
            double x1 = loopX(Math.min(end, until), from, until);
            g.setFill(slice == sounding ? Look.CELL_NOW : slice % 2 == 0 ? Look.SCREEN_ROW : Look.SCREEN);
            g.fillRect(x0, TOP - 4, x1 - x0, BOTTOM - TOP + 4);
            if (slice == chosen) {
                g.setStroke(Look.PLAYHEAD);
                g.setLineWidth(1.5);
                g.strokeRect(x0 + 1, TOP - 3, x1 - x0 - 2, BOTTOM - TOP + 2);
            }
            if (x1 - x0 >= 14) {
                g.setFont(SMALL);
                g.setTextAlign(TextAlignment.CENTER);
                g.setFill(slice == sounding || slice == chosen ? Look.SCREEN_TEXT : Look.SCREEN_DIM);
                g.fillText(String.valueOf(slice + 1), (x0 + x1) / 2, HEIGHT - 12);
            }
        }
        g.setStroke(Look.CELL_ON);
        g.setLineWidth(1);
        double width = RIGHT - LOOP_LEFT;
        for (int x = 0; x < width; x++) {
            int frame = from + (int) ((double) x / width * (until - from));
            int bucket = (int) ((long) frame * peaks.length / Math.max(1, frameCount));
            double peak = peaks[Math.min(peaks.length - 1, bucket)] * half;
            g.strokeLine(LOOP_LEFT + x + 0.5, middle - peak, LOOP_LEFT + x + 0.5, middle + peak);
        }
        if (passFraction >= 0) {
            double frame = passFraction * frameCount;
            if (frame >= from && frame < until) {
                double x = loopX(frame, from, until);
                g.setStroke(Look.PLAYHEAD);
                g.setLineWidth(2);
                g.strokeLine(x, TOP - 4, x, BOTTOM);
            }
        }
    }

    /** The slice of a cut loop drawn at {@code x} on {@code page}; -1 off the waveform. */
    static int loopSliceAt(double x, int page, int steps, int frameCount, List<Integer> starts) {
        if (x < LOOP_LEFT || x > RIGHT || starts.isEmpty()) {
            return -1;
        }
        int from = pageStart(page, steps, frameCount);
        int until = pageStart(page + 1, steps, frameCount);
        double frame = from + (x - LOOP_LEFT) / (RIGHT - LOOP_LEFT) * (until - from);
        int slice = -1;
        for (int at = 0; at < starts.size() && starts.get(at) <= frame; at++) {
            slice = at;
        }
        return slice;
    }

    /** Where in the audio a page of sixteen steps starts: the steps are equal parts of the pass. */
    private static int pageStart(int page, int steps, int frameCount) {
        int step = Math.min(steps, page * Steps.PADS);
        return (int) ((long) step * frameCount / Math.max(1, steps));
    }

    private static double loopX(double frame, int from, int until) {
        return LOOP_LEFT + (frame - from) / Math.max(1, until - from) * (RIGHT - LOOP_LEFT);
    }

    /** The loudest of {@code audio}, either ear, in each of {@code buckets} equal pieces of it. */
    static float[] peaks(Sample audio, int buckets) {
        float[][] ears = audio.copyStereo();
        int frames = audio.frameCount();
        float[] peaks = new float[buckets];
        for (int frame = 0; frame < frames; frame++) {
            int bucket = (int) ((long) frame * buckets / frames);
            float level = Math.max(Math.abs(ears[0][frame]), Math.abs(ears[1][frame]));
            peaks[bucket] = Math.max(peaks[bucket], level);
        }
        float loudest = 0;
        for (float peak : peaks) {
            loudest = Math.max(loudest, peak);
        }
        // as loud as the screen is tall: a quiet loop is drawn to be seen, not to be measured
        if (loudest > 0) {
            for (int bucket = 0; bucket < buckets; bucket++) {
                peaks[bucket] /= loudest;
            }
        }
        return peaks;
    }

    /** The drum row drawn at {@code y}, of {@code count}; -1 above or below them all. */
    static int drumRowAt(double y, int count) {
        int row = (int) Math.floor((y - TOP) / drumRowHeight(count));
        return y >= TOP && row < count ? row : -1;
    }

    private static double drumRowHeight(int count) {
        return Math.min(26, (BOTTOM - TOP) / Math.max(1, count));
    }

    void drawMelody(String name, Scale scale, List<Note> line, int bar, int bars, double beat) {
        GraphicsContext g = begin(name.toLowerCase(Locale.ROOT)
                + (scale.chosen() ? " · " + scale.toString().toLowerCase(Locale.ROOT) : ""));
        g.setFont(SMALL);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(Look.SCREEN_DIM);
        g.fillText("bar " + (bar + 1) + "/" + bars, RIGHT, 16);

        int low = 127;
        int high = 0;
        for (Note note : line) {
            int pitch = Steps.pitchOf(note);
            if (pitch >= 0) {
                low = Math.min(low, pitch);
                high = Math.max(high, pitch);
            }
        }
        if (low > high) {
            low = 48;
            high = 60;
        }
        // at least an octave, so a line of two notes is not drawn as two slabs
        while (high - low < 12) {
            high++;
            if (high - low < 12) {
                low--;
            }
        }
        int span = high - low + 1;
        double rowHeight = (BOTTOM - TOP) / span;
        double cell = (RIGHT - LEFT) / Steps.PADS;
        for (int pitch = low; pitch <= high; pitch++) {
            double y = BOTTOM - (pitch - low + 1) * rowHeight;
            boolean held = !scale.chosen() || scale.holds(pitch);
            g.setFill(held ? ((pitch - low) % 2 == 0 ? Look.SCREEN_ROW : Look.SCREEN_ROW_OTHER) : Look.SCREEN);
            g.fillRect(LEFT, y, RIGHT - LEFT, rowHeight);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Look.SCREEN_DIM);
        g.fillText(Steps.nameOf(high), 14, TOP + 6);
        g.fillText(Steps.nameOf(low), 14, BOTTOM - 4);

        double from = bar * (double) Session.BEATS_PER_BAR;
        double to = from + Session.BEATS_PER_BAR;
        for (Note note : line) {
            int pitch = Steps.pitchOf(note);
            double end = note.beat() + note.durationBeats();
            if (pitch < 0 || end <= from || note.beat() >= to) {
                continue;
            }
            double x0 = LEFT + Math.max(0, note.beat() - from) / Steps.PAD_BEATS * cell;
            double x1 = LEFT + Math.min(Session.BEATS_PER_BAR, end - from) / Steps.PAD_BEATS * cell;
            double y = BOTTOM - (pitch - low + 1) * rowHeight;
            boolean sounding = beat >= note.beat() && beat < end;
            g.setFill(sounding ? Look.SCREEN_TEXT : Look.CELL_ON);
            g.fillRoundRect(x0 + 1, y + 1, Math.max(2, x1 - x0 - 2), Math.max(2, rowHeight - 2), 3, 3);
        }
        if (beat >= from && beat < to) {
            double x = LEFT + (beat - from) / Steps.PAD_BEATS * cell;
            g.setStroke(Look.PLAYHEAD);
            g.setLineWidth(2);
            g.strokeLine(x, TOP - 2, x, BOTTOM);
        }
        beatNumbers(g, cell);
    }

    private GraphicsContext begin(String title) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Look.SCREEN);
        g.fillRoundRect(0, 0, WIDTH, HEIGHT, 10, 10);
        g.setTextBaseline(VPos.CENTER);
        g.setFont(TEXT);
        g.setFill(Look.SCREEN_TEXT);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(title, 14, 16);
        return g;
    }

    private void beatNumbers(GraphicsContext g, double cell) {
        g.setFont(SMALL);
        g.setFill(Look.SCREEN_DIM);
        g.setTextAlign(TextAlignment.CENTER);
        for (int pad = 0; pad < Steps.PADS; pad += 4) {
            g.fillText(String.valueOf(pad + 1), LEFT + pad * cell + cell / 2, HEIGHT - 12);
        }
    }

    /**
     * What each row is called on the box: "1 bd" is "bd", since the layer a row came from matters
     * to the code and not here, and a drum the kit has twice is "hh" and then "hh 2".
     */
    static List<String> names(List<GridRow> rows) {
        List<String> names = new ArrayList<>();
        for (GridRow row : rows) {
            String name = row.label().replaceFirst("^\\d+ ", "");
            String candidate = name;
            for (int number = 2; names.contains(candidate); number++) {
                candidate = name + " " + number;
            }
            names.add(candidate);
        }
        return names;
    }
}
