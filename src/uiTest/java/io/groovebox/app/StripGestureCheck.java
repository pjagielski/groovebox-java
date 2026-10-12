package io.groovebox.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/** Runs real slider-skin gestures without audio; separate from the offline test suite because it needs a display. */
public final class StripGestureCheck extends Application {
    private final List<Throwable> failures = new ArrayList<>();

    @Override public void start(Stage stage) {
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> failures.add(error));
        var values = new ArrayList<Double>();
        List<Strip> effects = List.of(Strip.continuous("Filter", 200, values::add),
                Strip.continuous("Dub", 200, values::add), Strip.continuous("Talkbox", 200, values::add));
        VBox root = new VBox(); effects.forEach(effect -> root.getChildren().add(effect.node()));
        stage.setScene(new Scene(root, 240, 250)); stage.show();
        Platform.runLater(() -> {
            try {
                root.applyCss(); root.layout();
                for (Strip effect : effects) {
                    Slider slider = (Slider) effect.node().lookup(".slider");
                    ToggleButton lock = (ToggleButton) effect.node().lookup(".toggle-button");
                    double neutral = slider.getValue();
                    Node thumb = slider.lookup(".thumb");
                    gesture(thumb, MouseButton.SECONDARY);
                    if (!lock.isSelected() || slider.getValue() != neutral) throw new AssertionError("Right drag moved slider or failed to lock");
                    gesture(slider.lookup(".track"), MouseButton.SECONDARY);
                    if (lock.isSelected() || slider.getValue() != neutral) throw new AssertionError("Right track drag failed to unlock");
                    mouse(thumb, MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 5, true);
                    mouse(thumb, MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 25, true);
                    double dragged = slider.getValue();
                    if (dragged <= neutral || values.getLast() != dragged) throw new AssertionError("Primary drag stopped publishing");
                    mouse(thumb, MouseEvent.MOUSE_RELEASED, MouseButton.PRIMARY, 25, false);
                    if (slider.getValue() != neutral || values.getLast() != -1) throw new AssertionError("Release failed to reset unlocked effect");
                    lock.fire();
                    gesture(thumb, MouseButton.PRIMARY);
                    if (slider.getValue() <= neutral || values.getLast() != slider.getValue()) throw new AssertionError("Locked effect was released");
                    effect.release();
                    if (lock.isSelected() || slider.getValue() != neutral || values.getLast() != -1) throw new AssertionError("Force release failed");
                }
                if (!failures.isEmpty()) throw new AssertionError("Slider drag failed", failures.getFirst());
                System.out.println("PASS: Filter, Dub, Talkbox; right thumb/track drag; primary drag; lock; release");
                Platform.exit();
            } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        });
    }

    private static void gesture(Node target, MouseButton button) {
        mouse(target, MouseEvent.MOUSE_PRESSED, button, 5, true);
        mouse(target, MouseEvent.MOUSE_DRAGGED, button, 15, true);
        mouse(target, MouseEvent.MOUSE_RELEASED, button, 15, false);
    }

    private static void mouse(Node target, EventType<MouseEvent> type, MouseButton button, double x, boolean down) {
        var scene = target.localToScene(x, 5);
        target.fireEvent(new MouseEvent(type, scene.getX(), scene.getY(), scene.getX(), scene.getY(), button, 1, false, false, false, false,
                down && button == MouseButton.PRIMARY, false, down && button == MouseButton.SECONDARY,
                false, false, type != MouseEvent.MOUSE_DRAGGED, new PickResult(target, scene.getX(), scene.getY())));
    }
}

/** Classpath entry point that leaves JavaFX startup to Application.launch. */
final class StripGestureLauncher {
    public static void main(String[] args) { Application.launch(StripGestureCheck.class, args); }
}
