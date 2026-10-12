package io.groovebox.app;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.css.PseudoClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/** An effect card played while held, with an explicit lock that keeps its setting after release. */
final class Strip {
    static final double HEIGHT = 46;
    private static final double OFF = -1;
    private final String name;
    private final List<String> zones;
    private final DoubleConsumer onChange;
    private final VBox card;
    private final Label state = new Label("OFF");
    private final ToggleButton lock = new ToggleButton("Lock");
    private final List<Button> buttons = new ArrayList<>();
    private final Slider slider;
    private final double neutral;
    private double active = OFF;
    private double chosen;
    private boolean pressing;
    private boolean updating;

    static Strip zones(String name, List<String> zones, double width, DoubleConsumer onChange) {
        return new Strip(name, List.copyOf(zones), width, onChange);
    }

    static Strip continuous(String name, double width, DoubleConsumer onChange) {
        return new Strip(name, List.of(), width, onChange);
    }

    private Strip(String name, List<String> zones, double width, DoubleConsumer onChange) {
        this.name = name; this.zones = zones; this.onChange = onChange;
        neutral = name.equals("Filter") ? 0.5 : 0; chosen = neutral;
        String id = name.toLowerCase(Locale.ROOT);
        Label title = new Label(name);
        title.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Look.web(Look.INK) + ";");
        state.setStyle("-fx-font: 10px Consolas; -fx-text-fill: #66685e;");
        lock.setId("fx-lock-" + id); lock.setAccessibleText("Lock " + name);
        lock.getStyleClass().add("fx-lock");
        lock.setOnAction(event -> { if (lock.isSelected()) choose(chosen); else if (!pressing) choose(OFF); });
        HBox header = new HBox(8, title, spacer(), state, lock); header.setAlignment(Pos.CENTER_LEFT);
        card = new VBox(5, header); card.setPadding(new Insets(7, 11, 7, 11));
        card.setId("fx-" + id); card.getStyleClass().add("fx-card");
        card.setMinWidth(0); card.setPrefWidth(width); card.setMaxWidth(Double.MAX_VALUE);
        card.setMinHeight(72); card.setPrefHeight(72); card.setMaxHeight(72);
        HBox.setHgrow(card, Priority.ALWAYS);
        if (zones.isEmpty()) {
            slider = new Slider(0, 1, neutral); slider.setBlockIncrement(0.025);
            slider.setId("fx-slider-" + id); slider.setAccessibleText("Global " + name);
            slider.getStyleClass().add("fx-slider");
            if (name.equals("Filter")) slider.getStyleClass().add("fx-filter-slider");
            slider.setPrefHeight(18); slider.setMinHeight(18); slider.setMaxHeight(18);
            slider.valueProperty().addListener((obs, was, value) -> {
                if (updating) return;
                chosen = value.doubleValue();
                if (pressing || lock.isSelected()) choose(chosen);
                paintSlider();
            });
            hold(slider, () -> choose(slider.getValue()));
            HBox guide = name.equals("Filter")
                    ? new HBox(caption("LOW PASS"), spacer(), caption("OFF"), spacer(), caption("HIGH PASS"))
                    : new HBox(caption("DRY"), spacer(), caption(name.equals("Dub") ? "FEEDBACK" : "VOWEL"));
            card.getChildren().addAll(slider, guide);
        } else {
            slider = null;
            HBox row = new HBox(4);
            for (int i = 0; i < zones.size(); i++) {
                int zone = i;
                Button button = new Button(zones.get(i));
                button.setId("fx-stutter-zone-" + i); button.setAccessibleText("Stutter " + zones.get(i));
                button.getStyleClass().add("fx-zone");
                button.setMinWidth(0); button.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(button, Priority.ALWAYS);
                hold(button, () -> { chosen = zone; choose(zone); });
                buttons.add(button); row.getChildren().add(button);
            }
            card.getChildren().add(row);
        }
        card.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                lock.setSelected(!lock.isSelected()); choose(lock.isSelected() ? chosen : OFF); event.consume();
            }
        });
        // The lock consumes the press, so its drag and release must not reach SliderSkin:
        // the skin has no drag origin for this gesture and would dereference null.
        card.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (event.isSecondaryButtonDown() || event.getButton() == MouseButton.SECONDARY) event.consume();
        });
        card.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) event.consume();
        });
    }

    Node node() { return card; }

    /** Stop and Release FX clear locks as well as the held effects. */
    void release() { pressing = false; lock.setSelected(false); chosen = neutral; choose(OFF); }

    private void hold(Node control, Runnable start) {
        control.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) { pressing = true; start.run(); }
        });
        control.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) endHold();
        });
        control.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || control instanceof Slider
                    && List.of(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.HOME, KeyCode.END).contains(event.getCode())) {
                pressing = true; start.run();
            }
        });
        control.addEventFilter(KeyEvent.KEY_RELEASED, event -> { if (pressing) endHold(); });
        control.focusedProperty().addListener((obs, was, focused) -> { if (!focused && pressing) endHold(); });
    }

    private void endHold() { pressing = false; if (!lock.isSelected()) choose(OFF); }

    private void choose(double value) {
        if (active != value) { active = value; onChange.accept(value); }
        boolean sounding = value >= 0 && (!zones.isEmpty() || (name.equals("Filter") ? value != neutral : value > 0));
        state.setText(!sounding ? "OFF" : zones.isEmpty() ? Math.round(value * 100) + "%" : zones.get((int) value));
        card.pseudoClassStateChanged(PseudoClass.getPseudoClass("active"), sounding);
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).pseudoClassStateChanged(PseudoClass.getPseudoClass("active"), value == i);
        if (slider != null) {
            updating = true;
            try { slider.setValue(value < 0 ? neutral : value); } finally { updating = false; }
            paintSlider();
        }
    }

    private void paintSlider() {
        Node track = slider.lookup(".track");
        if (track != null) {
            double percent = slider.getValue() * 100;
            track.setStyle("-fx-background-color: linear-gradient(to right, #db5830 0%, #db5830 " + percent
                    + "%, #eeeeed " + percent + "%, #eeeeed 100%);");
        }
    }

    private static Region spacer() { Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS); return space; }
    private static Label caption(String text) {
        Label label = new Label(text); label.setStyle("-fx-font: 9px Consolas; -fx-text-fill: #72746b;"); return label;
    }
}
