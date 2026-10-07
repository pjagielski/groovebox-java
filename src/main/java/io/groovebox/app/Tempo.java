package io.groovebox.app;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * The tempo, beside play where a hand reaches for both: a small screen of its own, turned like a
 * knob. Drag it up or down, or scroll over it, for half a beat a minute a step; hold Shift for a
 * tenth.
 */
final class Tempo {
    static final double WIDTH = 116;
    static final double SLOWEST = 40;
    static final double FASTEST = 200;
    private static final double PIXELS_PER_STEP = 4;

    private final Label number = new Label();
    private final HBox node;
    private final DoubleConsumer onChange;
    private double bpm;
    private double dragFrom;

    Tempo(double height, DoubleConsumer onChange) {
        this.onChange = onChange;
        number.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 20px; -fx-font-weight: bold;"
                + " -fx-text-fill: " + Look.web(Look.SCREEN_TEXT) + ";");
        Label unit = new Label("bpm");
        unit.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px; -fx-text-fill: "
                + Look.web(Look.SCREEN_DIM) + ";");
        node = new HBox(6, number, unit);
        node.setAlignment(Pos.CENTER);
        node.setPrefSize(WIDTH, height);
        node.setMinSize(WIDTH, height);
        node.setMaxSize(WIDTH, height);
        node.setCursor(Cursor.V_RESIZE);
        node.setStyle("-fx-background-color: " + Look.web(Look.SCREEN) + "; -fx-background-radius: 10;");
        Tooltip.install(node, new Tooltip("Tempo: drag up or down, or scroll; Shift for fine steps"));

        node.setOnMousePressed(event -> {
            dragFrom = event.getScreenY();
            event.consume();
        });
        node.setOnMouseDragged(event -> {
            double step = event.isShiftDown() ? 0.1 : 0.5;
            int steps = (int) ((dragFrom - event.getScreenY()) / PIXELS_PER_STEP);
            if (steps != 0) {
                dragFrom -= steps * PIXELS_PER_STEP;
                turn(steps * step);
            }
            event.consume();
        });
        node.setOnScroll(event -> {
            turn(Math.signum(event.getDeltaY()) * (event.isShiftDown() ? 0.1 : 0.5));
            event.consume();
        });
    }

    Node node() {
        return node;
    }

    /** Shows {@code next} without telling anyone: the tempo has been changed elsewhere, or loaded. */
    void show(double next) {
        bpm = next;
        number.setText(String.format(Locale.ROOT, "%.1f", next));
    }

    private void turn(double by) {
        double next = Math.max(SLOWEST, Math.min(FASTEST, Math.round((bpm + by) * 10) / 10.0));
        if (next != bpm) {
            show(next);
            onChange.accept(next);
        }
    }
}
