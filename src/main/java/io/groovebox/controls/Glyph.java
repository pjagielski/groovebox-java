package io.groovebox.controls;

import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

/**
 * The few shapes a button says better than a word: a triangle for play, a square for stop. Drawn
 * rather than shipped, like {@link StudioIcon} — nothing in this repository is a picture file.
 *
 * <p>They are the shapes every transport has had since tape, so they need no label; what they gain
 * over "Play" and "Stop" is that the eye finds them without reading, which is the point on a bar
 * reached for mid-take.
 */
public final class Glyph {

    private Glyph() {
    }

    /** A triangle pointing right, {@code size} across and the same tall. */
    public static Node play(double size, Color colour) {
        // a hair narrower than it is tall, and nudged right: a triangle centred on its bounding box
        // looks left of centre, because its mass is on the flat side
        double width = size * 0.88;
        Polygon triangle = new Polygon(0, 0, width, size / 2, 0, size);
        triangle.setFill(colour);
        triangle.setTranslateX(size * 0.06);
        return triangle;
    }

    /** A square, {@code size} across, with the corners just off sharp. */
    public static Node stop(double size, Color colour) {
        Rectangle square = new Rectangle(size * 0.82, size * 0.82, colour);
        square.setArcWidth(2);
        square.setArcHeight(2);
        return square;
    }
}
