package io.groovebox.controls;

import javafx.scene.Node;
import javafx.scene.input.MouseEvent;

/**
 * Keeps a control that is played by dragging from being taken for a drag of the view behind it.
 * Consuming its press and drag events stops a pannable ScrollPane from moving, but not from
 * switching to its move cursor: the pane does that when the scene announces a drag's start, a
 * separate event the control never saw. So the scene is told this press and drag are no drag
 * gesture, and the announcement is never made — and should one come anyway, it stops here.
 */
public final class NoPanning {

    private NoPanning() {
    }

    public static void on(Node control) {
        control.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> event.setDragDetect(false));
        control.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> event.setDragDetect(false));
        control.addEventHandler(MouseEvent.DRAG_DETECTED, MouseEvent::consume);
    }
}
