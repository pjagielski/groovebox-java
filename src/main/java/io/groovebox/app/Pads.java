package io.groovebox.app;

import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

/**
 * Sixteen pads in four groups of four, a light under the one that sounds. What a pad shows is
 * handed in whole: a hit and how hard, a note's name, a hold, or nothing; a click is handed out by
 * the pad's number. A click rather than a press, so that a pad can also be dragged from: a loop's
 * step dragged onto another pad gives that step its slice, and so does a slice dragged onto one
 * from the screen.
 */
final class Pads {
    static final double GAP = 6;
    /** What a pad being dragged carries: this, then the slice, -1 for a rest. */
    static final String SLICE = "groovebox-slice:";
    /** What {@link #setDragging}'s source says of a pad there is nothing to drag from. */
    static final int NOTHING = Integer.MIN_VALUE;
    static final double GROUP_GAP = 12;

    /** One pad as it is shown. */
    record Pad(Kind kind, String label, float strength) {
        enum Kind { EMPTY, HIT, HELD, REST, OFF }

        static final Pad EMPTY = new Pad(Kind.EMPTY, "", 0);
        /** A step that strikes nothing, so whatever was struck before it rings on through. */
        static final Pad REST = new Pad(Kind.REST, "–", 0);
        static final Pad HELD = new Pad(Kind.HELD, "—", 0);
        /** Past the end of a row with fewer steps than pads: there is nothing here to press. */
        static final Pad OFF = new Pad(Kind.OFF, "", 0);

        static Pad hit(String label, float strength) {
            return new Pad(Kind.HIT, label, strength);
        }
    }

    private final StackPane[] faces = new StackPane[Steps.PADS];
    private final Label[] labels = new Label[Steps.PADS];
    private final Region[] lights = new Region[Steps.PADS];
    private final HBox node = new HBox(GROUP_GAP);
    private int lit = -1;
    private IntUnaryOperator dragged = pad -> NOTHING;
    private BiConsumer<Integer, Integer> dropped = (pad, slice) -> { };

    /** Pads as wide as fit sixteen of them, in their groups, across {@code width}. */
    Pads(double width, IntConsumer onPress, IntConsumer onOtherPress) {
        double size = Math.floor((width - 3 * GROUP_GAP - 12 * GAP) / Steps.PADS);
        HBox group = null;
        for (int pad = 0; pad < Steps.PADS; pad++) {
            if (pad % 4 == 0) {
                group = new HBox(GAP);
                node.getChildren().add(group);
            }
            Label label = new Label();
            label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
            StackPane face = new StackPane(label);
            face.setPrefSize(size, size + 4);
            face.setMinSize(size, size + 4);
            int number = pad;
            face.setOnMouseClicked(event -> {
                if (!event.isStillSincePress()) {
                    // a drag, which is not a click
                    return;
                }
                if (event.getButton() == MouseButton.PRIMARY) {
                    onPress.accept(number);
                } else if (event.getButton() == MouseButton.SECONDARY) {
                    onOtherPress.accept(number);
                }
            });
            face.setOnDragDetected(event -> {
                int slice = dragged.applyAsInt(number);
                if (slice != NOTHING) {
                    startDrag(face, slice);
                }
                event.consume();
            });
            face.setOnDragOver(event -> {
                if (carriesSlice(event.getDragboard())) {
                    event.acceptTransferModes(TransferMode.COPY);
                }
                event.consume();
            });
            face.setOnDragEntered(event -> {
                if (carriesSlice(event.getDragboard())) {
                    face.setScaleX(1.1);
                    face.setScaleY(1.1);
                }
            });
            face.setOnDragExited(event -> {
                face.setScaleX(1);
                face.setScaleY(1);
            });
            face.setOnDragDropped(event -> {
                boolean done = carriesSlice(event.getDragboard());
                if (done) {
                    String carried = event.getDragboard().getString();
                    dropped.accept(number, Integer.parseInt(carried.substring(SLICE.length())));
                }
                event.setDropCompleted(done);
                event.consume();
            });
            Region light = new Region();
            light.setPrefSize(size, 4);
            light.setMaxHeight(4);
            VBox column = new VBox(5, face, light);
            column.setAlignment(Pos.TOP_CENTER);
            faces[pad] = face;
            labels[pad] = label;
            lights[pad] = light;
            group.getChildren().add(column);
            show(pad, Pad.EMPTY);
            light(pad, false);
        }
    }

    Node node() {
        return node;
    }

    /**
     * What dragging does: {@code source} names the slice a pad's step plays, -1 for a rest, or
     * {@link #NOTHING} where there is nothing to drag; {@code target} is told the pad a slice was
     * dropped on, and the slice.
     */
    void setDragging(IntUnaryOperator source, BiConsumer<Integer, Integer> target) {
        dragged = source;
        dropped = target;
    }

    /** A slice picked up from {@code from}, carried looking like the pad it would land as. */
    static void startDrag(Node from, int slice) {
        Dragboard board = from.startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        content.putString(SLICE + slice);
        board.setContent(content);
        board.setDragView(carried(slice < 0 ? "–" : String.valueOf(slice + 1)));
    }

    private static boolean carriesSlice(Dragboard board) {
        return board.hasString() && board.getString().startsWith(SLICE);
    }

    /** A small pad with {@code label} on it, which is what the hand is seen to carry. */
    private static Image carried(String label) {
        Canvas pad = new Canvas(40, 42);
        GraphicsContext g = pad.getGraphicsContext2D();
        g.setFill(Color.web(Look.PAD_STRONG));
        g.fillRoundRect(0, 0, 40, 42, 8, 8);
        g.setFill(Color.web(Look.PAD_TEXT));
        g.setFont(Font.font(null, FontWeight.BOLD, 14));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(label, 20, 21);
        SnapshotParameters clear = new SnapshotParameters();
        clear.setFill(Color.TRANSPARENT);
        return pad.snapshot(clear, null);
    }

    void show(Pad[] pads) {
        for (int pad = 0; pad < Steps.PADS; pad++) {
            show(pad, pads[pad]);
        }
    }

    /** The light under {@code pad}, and only that one; -1 puts them all out. */
    void light(int pad) {
        if (pad == lit) {
            return;
        }
        if (lit >= 0) {
            light(lit, false);
        }
        lit = pad;
        if (pad >= 0) {
            light(pad, true);
        }
    }

    private void light(int pad, boolean on) {
        lights[pad].setStyle("-fx-background-radius: 2; -fx-background-color: "
                + (on ? Look.PLAY : "transparent") + ";");
    }

    private void show(int pad, Pad shown) {
        String fill;
        String edge;
        String text = Look.PAD_TEXT;
        switch (shown.kind()) {
            case HIT -> {
                fill = shown.strength() >= 0.95f ? Look.PAD_STRONG : Look.PAD_HIT;
                edge = fill;
            }
            case HELD -> {
                fill = Look.PAD_HELD;
                edge = Look.PAD_HIT;
                text = Look.PAD_HELD_TEXT;
            }
            case OFF -> {
                fill = Look.web(Look.BODY);
                edge = Look.web(Look.BODY_EDGE);
            }
            case REST -> {
                fill = "white";
                edge = "white";
                text = Look.web(Look.QUIET_INK);
            }
            default -> {
                fill = Look.PAD_EMPTY;
                edge = Look.web(Look.BODY_EDGE);
            }
        }
        // a soft hit is a paler pad: the accent is seen before it is read
        double opacity = shown.kind() == Pad.Kind.HIT ? 0.55 + 0.45 * Math.min(1, shown.strength()) : 1;
        // a rest is drawn as the sketch has it: an empty pad with a dashed edge
        String dashed = shown.kind() == Pad.Kind.REST
                ? " -fx-border-color: " + Look.web(Look.QUIET_INK) + "; -fx-border-style: segments(4, 3);"
                + " -fx-border-radius: 7; -fx-border-width: 1.2;"
                : "";
        faces[pad].setStyle("-fx-background-color: " + edge + ", " + fill + "; -fx-background-insets: 0, 1.5;"
                + " -fx-background-radius: 7, 6; -fx-cursor: hand;" + dashed);
        faces[pad].setOpacity(opacity);
        faces[pad].setDisable(shown.kind() == Pad.Kind.OFF);
        labels[pad].setText(shown.label());
        labels[pad].setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + text + ";");
    }
}
