package io.groovebox.app;

import io.groovebox.audio.MixLevels;
import io.groovebox.session.Session;
import io.groovebox.session.StudioTrack;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Compact channel strips for the whole workspace, with the local master always outside the scrolling tracks. */
final class MixerView {
    private final Session session;
    private final IntConsumer select;
    private final Runnable changed;
    private final HBox channels = new HBox(6);
    private final ScrollPane scroll = new ScrollPane(channels);
    private final VBox root;
    private final HBox inspector;
    private static final double METER_HEIGHT = 77;
    private final List<Channel> strips = new ArrayList<>();
    private final Channel master;
    private final Label detail = new Label();
    private final Button range = new Button("MIDI range…");
    private String structure = "";
    private boolean syncing;

    MixerView(Session session, IntConsumer select, Runnable edit, IntConsumer editRange, Runnable changed) {
        this.session = session; this.select = select; this.changed = changed;
        scroll.setId("mixer-tracks"); scroll.setMinWidth(0);
        scroll.setFitToHeight(true); scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: " + Look.web(Look.BODY) + "; -fx-background-color: transparent;");
        HBox.setHgrow(scroll, Priority.ALWAYS);
        master = new Channel(-1);
        HBox bank = new HBox(10, scroll, master.node);
        bank.setPrefHeight(212); bank.setMinHeight(212); bank.setMaxHeight(212);
        detail.setStyle("-fx-text-fill: " + Look.web(Look.QUIET_INK) + "; -fx-font-size: 12px;");
        detail.setMinWidth(0); detail.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(detail, Priority.ALWAYS);
        range.setOnAction(event -> editRange.accept(session.tracks().selectedIndex()));
        Button instrument = new Button("Edit instrument →"); instrument.setOnAction(event -> edit.run());
        inspector = new HBox(10, detail, range, instrument); inspector.setAlignment(Pos.CENTER_LEFT);
        inspector.setMinWidth(0); HBox.setHgrow(inspector, Priority.ALWAYS);
        inspector.setMinHeight(30); inspector.setPrefHeight(30);
        root = new VBox(bank); root.setId("mixer-view");
        refresh(MixLevels.SILENT);
    }

    Node node() { return root; }
    Node inspector() { return inspector; }

    void refresh(MixLevels levels) {
        String next = session.tracks().tracks().stream().map(t -> t.getClass().getName() + ":" + t.name() + ":" + kind(t)).toList().toString();
        syncing = true;
        try {
            if (!next.equals(structure)) {
                structure = next; channels.getChildren().clear(); strips.clear();
                for (int i = 0; i < session.tracks().size(); i++) {
                    Channel channel = new Channel(i); strips.add(channel); channels.getChildren().add(channel.node);
                }
            }
            int played = 0;
            for (Channel strip : strips) {
                StudioTrack track = session.tracks().get(strip.index);
                int count = track instanceof StudioTrack.Drums ? Math.max(1, session.rows().size()) : 1;
                float peak = 0;
                for (int i = 0; i < count; i++) peak = Math.max(peak, levels.track(played++));
                strip.update(track.gain(), track.muted(), track.soloed(), peak,
                        session.tracks().selectedIndex() == strip.index);
            }
            master.update(session.masterGain(), session.masterMuted(), false, levels.master(), false);
            StudioTrack selected = session.tracks().selected();
            detail.setText(selected.name() + " · " + kind(selected));
            boolean midi = selected instanceof StudioTrack.Melody melody && melody.window() != null;
            range.setVisible(midi); range.setManaged(midi);
        } finally { syncing = false; }
    }

    private static String kind(StudioTrack track) {
        return switch (track) {
            case StudioTrack.Drums drums -> "Drum kit";
            case StudioTrack.Loop loop -> "Audio loop";
            case StudioTrack.Melody melody -> (melody.window() == null ? "Notes" : "MIDI")
                    + (melody.instrument() == null ? "" : " · " + melody.instrument().patch());
        };
    }

    private final class Channel {
        final int index;
        final VBox node;
        final Slider gain = new Slider(0, 1, 1);
        final Label value = new Label();
        final ToggleButton mute = new ToggleButton("M"), solo = new ToggleButton("S");
        final Canvas meter = new Canvas(10, METER_HEIGHT);
        float heldPeak;

        Channel(int index) {
            this.index = index;
            boolean output = index < 0;
            Label number = new Label(output ? "OUTPUT" : String.format("%02d", index + 1));
            number.setStyle("-fx-font-family: Consolas; -fx-font-size: 10px; -fx-text-fill: #797d70;");
            Button title = new Button(output ? "Master" : session.tracks().get(index).name());
            title.setMaxWidth(Double.MAX_VALUE); title.setMinWidth(0);
            title.setStyle("-fx-font-weight: bold; -fx-background-color: transparent; -fx-padding: 0; -fx-text-fill: "
                    + (output ? "#d2e4d7" : Look.web(Look.INK)) + ";");
            title.setTooltip(new Tooltip(title.getText()));
            title.setOnAction(event -> { if (!output) select.accept(index); });
            Label kind = new Label(output ? "Stereo out" : kind(session.tracks().get(index)));
            kind.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (output ? "#9eb5a7" : Look.web(Look.QUIET_INK)) + ";");
            kind.setMaxWidth(78);
            mute.setId(output ? "master-mute" : "mixer-mute-" + index);
            solo.setId("mixer-solo-" + index);
            mute.setTooltip(new Tooltip(output ? "Mute local stereo output" : "Mute track"));
            solo.setTooltip(new Tooltip("Solo track"));
            for (ToggleButton toggle : List.of(mute, solo)) toggle.setPrefWidth(32);
            mute.setAccessibleText(output ? "Mute master" : "Mute " + title.getText());
            solo.setAccessibleText("Solo " + title.getText());
            mute.setOnAction(event -> {
                if (output) session.setMasterMuted(mute.isSelected()); else session.setMuted(index, mute.isSelected());
                changed.run();
            });
            solo.setOnAction(event -> { session.setSoloed(index, solo.isSelected()); changed.run(); });
            HBox switches = output ? new HBox(mute) : new HBox(5, mute, solo);
            switches.setAlignment(Pos.CENTER);
            value.setStyle("-fx-font-family: Consolas; -fx-font-size: 15px; -fx-text-fill: "
                    + (output ? "#c0ddce" : Look.web(Look.INK)) + ";");
            gain.setId(output ? "master-gain" : "mixer-gain-" + index);
            gain.setAccessibleText(output ? "Master volume" : title.getText() + " volume");
            gain.setOrientation(Orientation.VERTICAL); gain.setPrefHeight(METER_HEIGHT); gain.setMaxHeight(METER_HEIGHT);
            gain.setBlockIncrement(0.02);
            gain.valueProperty().addListener((obs, was, now) -> {
                if (syncing) return;
                if (output) session.setMasterGain(now.floatValue()); else session.setGain(index, now.floatValue());
                value.setText(decibels(now.floatValue())); changed.run();
            });
            gain.setOnMouseClicked(event -> { if (event.getClickCount() == 2) gain.setValue(1); });
            HBox controls = new HBox(8, gain, meter); controls.setAlignment(Pos.CENTER);
            node = new VBox(5, number, title, kind, switches, value, controls);
            node.setPadding(new Insets(10, 7, 8, 7)); node.setAlignment(Pos.TOP_CENTER);
            node.setPrefWidth(output ? 94 : 82); node.setMinWidth(output ? 94 : 82); node.setMaxWidth(output ? 94 : 82);
            node.setId(output ? "mixer-master" : "mixer-channel-" + index);
        }

        void update(float level, boolean muted, boolean soloed, float peak, boolean selected) {
            if (!gain.isValueChanging() && !gain.isPressed()) gain.setValue(level);
            value.setText(decibels(level)); mute.setSelected(muted); solo.setSelected(soloed);
            node.setStyle("-fx-background-color: " + (index < 0 ? Look.web(Look.SCREEN) : selected ? "#f3ede1" : "#e2dfd5")
                    + "; -fx-background-radius: 9; -fx-border-radius: 9; -fx-border-color: "
                    + (selected ? Look.PLAY : Look.web(Look.BODY_EDGE)) + ";");
            mute.setStyle(muted ? "-fx-base: #fac775;" : ""); solo.setStyle(soloed ? "-fx-base: #5dcaa5;" : "");
            heldPeak = Math.max(peak, heldPeak * 0.89f);
            var graphics = meter.getGraphicsContext2D();
            graphics.setFill(Look.SCREEN); graphics.fillRect(0, 0, 10, METER_HEIGHT);
            double height = heldPeak <= 0 ? 0 : Math.max(0, Math.min(1, (20 * Math.log10(heldPeak) + 48) / 48)) * METER_HEIGHT;
            graphics.setFill(heldPeak >= 1 ? Color.web("#e77750") : Look.CELL_ON);
            graphics.fillRect(1, METER_HEIGHT - height, 8, height);
            graphics.setFill(Look.SCREEN); for (int y = 0; y < METER_HEIGHT; y += 7) graphics.fillRect(0, y, 10, 2);
        }
    }

    private static String decibels(float gain) {
        return gain <= 0 ? "−∞ dB" : String.format(Locale.ROOT, "%.1f dB", 20 * Math.log10(gain));
    }
}
