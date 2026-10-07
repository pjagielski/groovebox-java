package io.groovebox.controls;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The look of {@link SynthControls} lent to the rest of the studio: the same framed group with a
 * small coloured heading, and the same knob, so the melody and MIDI controls sit next to the synth
 * panel without looking like they came from a different program.
 */
public final class StudioPanels {

    private StudioPanels() {
    }

    /** A framed group on a light background, with {@code title} above its rows. */
    public static VBox frame(String title, Node... rows) {
        Label header = new Label(title.toUpperCase());
        header.setStyle("-fx-text-fill: " + Theme.web(Theme.LIGHT.mutedText())
                + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        VBox box = new VBox(10, header);
        box.getChildren().addAll(rows);
        box.setPadding(new Insets(12, 14, 14, 14));
        box.setStyle("-fx-background-color: " + Theme.web(Theme.LIGHT.panel())
                + "; -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: "
                + Theme.web(Theme.LIGHT.panelBorder()) + ";");
        return box;
    }

    /** A row of controls, sitting on the knobs' baseline so their readouts line up. */
    public static HBox row(Node... children) {
        HBox row = new HBox(10, children);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.setPadding(new Insets(0, 0, 4, 0));
        return row;
    }

    /** Knobs spread evenly across the frame's width, as they are in the synth panel's groups. */
    public static HBox knobRow(Node... knobs) {
        HBox row = new HBox();
        row.setAlignment(Pos.BOTTOM_CENTER);
        for (Node knob : knobs) {
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            row.getChildren().addAll(gap, knob);
        }
        Region tail = new Region();
        HBox.setHgrow(tail, Priority.ALWAYS);
        row.getChildren().add(tail);
        return row;
    }

    /** A knob on a linear scale, drawn and handled exactly like the synth panel's. */
    public static PanelKnob knob(String label, double min, double max, String unit, int decimals,
                                 double initial) {
        return new PanelKnob(new Knob(Param.linear(label, min, max, unit, decimals, initial),
                Theme.Accent.FILTER, 62, Theme.LIGHT));
    }

    /** The size of a knob on a box rather than a panel: a hardware groovebox's, black on cream. */
    private static final double BOX_KNOB = 50;

    /** A box's knob on a linear scale. */
    public static PanelKnob boxKnob(String label, double min, double max, String unit, int decimals,
                                    double initial) {
        return new PanelKnob(new Knob(Param.linear(label, min, max, unit, decimals, initial),
                Theme.Accent.FILTER, BOX_KNOB, Theme.BOX));
    }

    /** A box's knob whose every turn is worth the same ratio rather than the same amount: a cutoff, a time. */
    public static PanelKnob boxExponentialKnob(String label, double min, double max, String unit, int decimals,
                                               double initial) {
        return new PanelKnob(new Knob(Param.exponential(label, min, max, unit, decimals, initial),
                Theme.Accent.FILTER, BOX_KNOB, Theme.BOX));
    }

    /** A box's knob that goes two ways from its middle: a tuning, up or down. */
    public static PanelKnob boxBipolarKnob(String label, double min, double max, String unit, int decimals,
                                           double initial) {
        return new PanelKnob(new Knob(Param.bipolar(label, min, max, unit, decimals, initial),
                Theme.Accent.FILTER, BOX_KNOB, Theme.BOX));
    }

    /** A box's selector switch between {@code names}; its value is the index of the one it points at. */
    public static PanelKnob boxStepKnob(String label, List<String> names, int initial) {
        PanelKnob knob = new PanelKnob(new Knob(Param.steps(label, names.toArray(String[]::new)),
                Theme.Accent.FILTER, BOX_KNOB, Theme.BOX));
        knob.setValue(initial);
        return knob;
    }
}
