package io.groovebox.controls;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * A knob like the ones on a plugin's panel, drawn on a {@link Canvas}: an arc for the value, a
 * pointer, the parameter's name above and its value below. JavaFX has no knob of its own, but it
 * has everything a knob is made of.
 *
 * <p>It behaves the way a knob in a DAW does: drag up or down to turn it, hold Shift to go slowly,
 * use the scroll wheel, double-click to go back to the starting value, and, once it has focus, the
 * arrow keys. The value is a {@link #position} from 0 to 1; {@link Param} turns that into hertz,
 * seconds or cents.
 */
final class Knob extends VBox {

    private static final double FULL_TURN_PIXELS = 180;
    private static final double SHIFT_SLOWDOWN = 5;
    /** How far the hand moves to click a stepped knob on by one, whatever it is stepping between. */
    private static final double PIXELS_PER_STEP = 28;
    private static final double SWEEP_DEGREES = 270;
    private static final double START_DEGREES = 225;

    private final DoubleProperty position = new SimpleDoubleProperty();
    private final Param param;
    private final Theme.Accent accent;
    private final Canvas dial;
    private final Label name;
    private final Label readout = new Label();

    private Theme theme;
    private double dragFrom;

    Knob(Param param, Theme.Accent accent, double size, Theme theme) {
        super(4);
        this.param = param;
        this.accent = accent;
        this.theme = theme;
        this.dial = new Canvas(size, size);
        this.name = new Label(param.label().toUpperCase());
        setAlignment(Pos.CENTER);
        setFocusTraversable(true);
        getChildren().addAll(name, dial, readout);
        Tooltip.install(this, new Tooltip(param.label()
                + "\ndrag up or down, Shift for fine steps, double-click for the starting value"));

        position.addListener((property, before, after) -> draw());
        // every one of these is consumed: a knob inside a pannable ScrollPane would otherwise drag
        // the whole view along with the value, cursor and all
        NoPanning.on(this);
        setOnMousePressed(event -> {
            requestFocus();
            dragFrom = event.getSceneY();
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                setValue(param.initial());
            }
            event.consume();
        });
        setOnMouseDragged(event -> {
            double pixels = dragFrom - event.getSceneY();
            dragFrom = event.getSceneY();
            nudge(pixels / FULL_TURN_PIXELS / (event.isShiftDown() ? SHIFT_SLOWDOWN : 1));
            event.consume();
        });
        setOnScroll(event -> {
            nudge(Math.signum(event.getDeltaY()) * (event.isShiftDown() ? 0.002 : 0.02));
            event.consume();
        });
        setOnKeyPressed(event -> {
            double step = event.isShiftDown() ? 0.002 : 0.02;
            if (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.RIGHT) {
                nudge(step);
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.LEFT) {
                nudge(-step);
                event.consume();
            }
        });
        focusedProperty().addListener((property, before, after) -> draw());

        position.set(clamp(param.positionOf(param.initial())));
        setTheme(theme);
    }

    DoubleProperty position() {
        return position;
    }

    double value() {
        double turned = param.valueOf(position.get());
        // a stepped knob is a selector switch: it reads out the thing it is pointing nearest to
        return param.stepped() ? param.step(turned) : turned;
    }

    /**
     * Where the pointer is drawn. A stepped knob shows the thing it has landed on rather than the
     * place the hand left it, so it looks like it clicks; what the hand actually did stays in
     * {@code position}, or a drag would be rounded away a pixel at a time and the knob would not
     * move at all.
     */
    private double shownPosition() {
        return param.stepped()
                ? clamp(param.positionOf(param.step(param.valueOf(position.get()))))
                : position.get();
    }

    void setValue(double value) {
        position.set(clamp(param.positionOf(value)));
        draw();
    }

    void setTheme(Theme theme) {
        this.theme = theme;
        name.setStyle("-fx-text-fill: " + Theme.web(theme.mutedText())
                + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        readout.setStyle("-fx-text-fill: " + Theme.web(theme.text()) + "; -fx-font-size: 11px;"
                + " -fx-font-family: 'Consolas', 'Menlo', monospace;");
        draw();
    }

    private void nudge(double by) {
        if (param.stepped()) {
            // a step every PIXELS_PER_STEP however many steps there are. Without this the travel is
            // divided among them, so a three-way switch needs ninety pixels to move and a nine-way
            // one twenty-two - the wrong way round, and the three-way one felt broken.
            by *= FULL_TURN_PIXELS / (PIXELS_PER_STEP * Math.max(1, param.names().size() - 1));
        }
        position.set(clamp(position.get() + by));
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void draw() {
        double size = dial.getWidth();
        double inset = 7;
        double span = size - 2 * inset;
        double centre = size / 2;
        double radius = span / 2;
        double turned = shownPosition();
        double angle = START_DEGREES - SWEEP_DEGREES * turned;
        Color accentColour = theme.accent(accent);

        GraphicsContext g = dial.getGraphicsContext2D();
        g.clearRect(0, 0, size, size);
        g.setLineCap(StrokeLineCap.ROUND);

        g.setStroke(theme.track());
        g.setLineWidth(5);
        g.strokeArc(inset, inset, span, span, START_DEGREES, -SWEEP_DEGREES, ArcType.OPEN);

        g.setStroke(accentColour);
        if (param.bipolar()) {
            g.strokeArc(inset, inset, span, span, 90, angle - 90, ArcType.OPEN);
        } else {
            g.strokeArc(inset, inset, span, span, START_DEGREES, -SWEEP_DEGREES * turned, ArcType.OPEN);
        }

        g.setFill(theme.dialFace());
        g.fillOval(centre - radius + 6, centre - radius + 6, 2 * (radius - 6), 2 * (radius - 6));
        g.setStroke(isFocused() ? theme.dialEdgeFocused() : theme.dialEdge());
        g.setLineWidth(1);
        g.strokeOval(centre - radius + 6, centre - radius + 6, 2 * (radius - 6), 2 * (radius - 6));

        double radians = Math.toRadians(angle);
        g.setStroke(theme.pointer());
        g.setLineWidth(2.5);
        g.strokeLine(centre + Math.cos(radians) * (radius - 13), centre - Math.sin(radians) * (radius - 13),
                centre + Math.cos(radians) * (radius - 5), centre - Math.sin(radians) * (radius - 5));

        readout.setText(param.format(value()));
    }
}
