package io.groovebox.controls;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/**
 * The window's icon, drawn rather than shipped: the same knob the panels are made of, on the dark
 * background they use. Java Sound has no pictures in it, so neither does this repository.
 *
 * <p>One image per size the desktop is likely to ask for, so the taskbar picks a crisp one instead
 * of scaling a single bitmap.
 */
public final class StudioIcon {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private StudioIcon() {
    }

    public static List<Image> sizes() {
        return Arrays.stream(SIZES).mapToObj(StudioIcon::icon).toList();
    }

    /**
     * Drawn on a canvas, then handed over as PNG bytes: the window manager ignores the
     * {@link WritableImage} a snapshot returns, and takes an image read from a stream.
     */
    private static Image icon(int size) {
        WritableImage drawn = draw(size);
        BufferedImage png = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = drawn.getPixelReader();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                png.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        var bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(png, "png", bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new Image(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static WritableImage draw(int size) {
        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();
        double inset = size * 0.08;
        double span = size - 2 * inset;
        double centre = size / 2.0;
        double radius = span / 2;
        double angle = 225 - 270 * 0.68;

        g.setFill(Color.web("#16181d"));
        g.fillRoundRect(0, 0, size, size, size * 0.22, size * 0.22);

        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineWidth(Math.max(1.5, size * 0.075));
        g.setStroke(Color.web("#343a40"));
        g.strokeArc(inset, inset, span, span, 225, -270, ArcType.OPEN);
        g.setStroke(Color.web("#f59f00"));
        g.strokeArc(inset, inset, span, span, 225, -270 * 0.68, ArcType.OPEN);

        double dial = radius - size * 0.17;
        g.setFill(Color.web("#212529"));
        g.fillOval(centre - dial, centre - dial, 2 * dial, 2 * dial);

        double radians = Math.toRadians(angle);
        g.setStroke(Color.web("#f8f9fa"));
        g.setLineWidth(Math.max(1.2, size * 0.06));
        g.strokeLine(centre + Math.cos(radians) * dial * 0.35, centre - Math.sin(radians) * dial * 0.35,
                centre + Math.cos(radians) * dial * 0.95, centre - Math.sin(radians) * dial * 0.95);

        SnapshotParameters transparent = new SnapshotParameters();
        transparent.setFill(Color.TRANSPARENT);
        return canvas.snapshot(transparent, new WritableImage(size, size));
    }
}
