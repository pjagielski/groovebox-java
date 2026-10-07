package io.groovebox.app;

import javafx.scene.paint.Color;

/** The groovebox's colours: a cream box, a green-on-black screen and amber pads. */
final class Look {
    static final Color BODY = Color.web("#edeae2");
    static final Color BODY_EDGE = Color.web("#c9c4b8");
    static final Color ROOM = Color.web("#d9d5ca");
    static final Color INK = Color.web("#2c2c2a");
    static final Color QUIET_INK = Color.web("#888780");

    static final Color SCREEN = Color.web("#1a221e");
    static final Color SCREEN_ROW = Color.web("#202b26");
    static final Color SCREEN_ROW_OTHER = Color.web("#1c2621");
    static final Color SCREEN_TEXT = Color.web("#9fe1cb");
    static final Color SCREEN_DIM = Color.web("#5f7a6e");
    static final Color CELL_ON = Color.web("#5dcaa5");
    static final Color CELL_SOFT = Color.web("#2f6b57");
    static final Color CELL_OFF = Color.web("#222d28");
    static final Color CELL_NOW = Color.web("#33493f");
    static final Color PLAYHEAD = Color.web("#f0997b");

    static final String PLAY = "#d85a30";
    static final String PAD_HIT = "#fac775";
    static final String PAD_STRONG = "#ef9f27";
    static final String PAD_HELD = "#faeeda";
    static final String PAD_EMPTY = "#f7f5ef";
    static final String PAD_TEXT = "#633806";
    static final String PAD_HELD_TEXT = "#ba7517";
    static final String CHIP_ON = "#2c2c2a";
    static final String CHIP_ON_TEXT = "#f1efe8";
    static final String CHIP_OFF = "#d3d1c7";
    static final String CHIP_OFF_TEXT = "#444441";

    private Look() {
    }

    static String web(Color color) {
        return String.format("#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }
}
