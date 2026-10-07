package io.groovebox.app;

import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * A performance effect as a box has it: a short strip held with a finger. Press and the effect
 * comes in where the finger is; slide left or right and it follows; let go and it stops. A
 * right-click locks it where it is, and another lets it go.
 *
 * <p>The studio's {@code FxStrip} plays the same effects standing up, with a Lock button under it;
 * a box has no room for either, so this one lies down and shows its lock by its colour.
 *
 * <p>A strip either has zones, left to right — the stutter's slice lengths — or plays any value
 * from 0 at the left to 1 at the right. The listener hears the zone's index or the value, or -1
 * when the effect should stop.
 */
final class Strip {
    static final double HEIGHT = 46;
    private static final double OFF = -1;
    private static final Color FACE = Color.web(Look.CHIP_OFF);
    private static final Color ON = Color.web(Look.PAD_STRONG);
    private static final Color LOCKED = Color.web(Look.PLAY);
    private static final Color TEXT = Color.web(Look.CHIP_OFF_TEXT);
    private static final Color ON_TEXT = Color.web(Look.PAD_TEXT);

    private final String name;
    private final List<String> zones;
    private final DoubleConsumer onChange;
    private final Canvas canvas;
    private double active = OFF;
    private boolean pressing;
    private boolean locked;

    static Strip zones(String name, List<String> zones, double width, DoubleConsumer onChange) {
        return new Strip(name, List.copyOf(zones), width, onChange);
    }

    static Strip continuous(String name, double width, DoubleConsumer onChange) {
        return new Strip(name, List.of(), width, onChange);
    }

    private Strip(String name, List<String> zones, double width, DoubleConsumer onChange) {
        this.name = name;
        this.zones = zones;
        this.onChange = onChange;
        this.canvas = new Canvas(width, HEIGHT);
        canvas.setCursor(javafx.scene.Cursor.HAND);
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                // a lock takes what is playing, or where the click was when nothing is
                locked = !locked;
                choose(locked ? (pressing && active >= 0 ? active : valueAt(event.getX())) : pressing ? active : OFF);
            } else if (event.getButton() == MouseButton.PRIMARY) {
                pressing = true;
                choose(valueAt(event.getX()));
            }
            draw();
            event.consume();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (pressing) {
                choose(valueAt(event.getX()));
            }
            event.consume();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                pressing = false;
                if (!locked) {
                    choose(OFF);
                }
            }
            event.consume();
        });
        draw();
    }

    Node node() {
        return canvas;
    }

    /** Unlocks and stops, as if the finger had been lifted: what Stop does to every strip. */
    void release() {
        pressing = false;
        locked = false;
        choose(OFF);
    }

    private double valueAt(double x) {
        double width = canvas.getWidth();
        if (zones.isEmpty()) {
            return Math.max(0, Math.min(1, x / width));
        }
        return Math.max(0, Math.min(zones.size() - 1, (int) (x / width * zones.size())));
    }

    private void choose(double value) {
        if (value != active) {
            active = value;
            onChange.accept(value);
        }
        draw();
    }

    private void draw() {
        double width = canvas.getWidth();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, width, HEIGHT);
        g.setFill(FACE);
        g.fillRoundRect(0, 0, width, HEIGHT, 12, 12);
        Color fill = locked ? LOCKED : ON;
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        if (active < 0) {
            g.setFill(TEXT);
            g.setFont(Font.font(14));
            g.fillText(name.toLowerCase(), width / 2, HEIGHT / 2);
            return;
        }
        if (zones.isEmpty()) {
            g.setFill(fill);
            g.fillRoundRect(0, 0, Math.max(12, active * width), HEIGHT, 12, 12);
            g.setFill(ON_TEXT);
            g.setFont(Font.font(null, FontWeight.BOLD, 14));
            g.fillText(name.toLowerCase(), width / 2, HEIGHT / 2);
        } else {
            double zone = width / zones.size();
            g.setFill(fill);
            g.fillRoundRect(active * zone, 0, zone, HEIGHT, 12, 12);
            g.setFont(Font.font("Consolas", FontWeight.BOLD, 12));
            for (int index = 0; index < zones.size(); index++) {
                g.setFill(index == active ? ON_TEXT : TEXT);
                g.fillText(zones.get(index), (index + 0.5) * zone, HEIGHT / 2);
            }
        }
    }
}
