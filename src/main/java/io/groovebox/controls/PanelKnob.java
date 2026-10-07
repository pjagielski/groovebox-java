package io.groovebox.controls;

import javafx.scene.layout.Region;

import java.util.function.DoubleConsumer;

/** One of {@link SynthControls}' knobs, handed to the rest of the studio behind a plain value. */
public final class PanelKnob {

    private final Knob knob;

    PanelKnob(Knob knob) {
        this.knob = knob;
    }

    public Region node() {
        return knob;
    }

    public double value() {
        return knob.value();
    }

    public void setValue(double value) {
        knob.setValue(value);
    }

    /** Called whenever the knob is turned, with the value it now shows. */
    public void setOnChange(DoubleConsumer listener) {
        knob.position().addListener((property, before, after) -> listener.accept(knob.value()));
    }
}
