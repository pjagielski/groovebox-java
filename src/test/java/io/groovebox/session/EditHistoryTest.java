package io.groovebox.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditHistoryTest {

    @Test
    void undoWalksBackThroughTheStatesAndRedoWalksForwardAgain() {
        var history = new EditHistory<String>(10);
        history.remember("one");
        history.remember("two");

        assertEquals("two", history.undo("three"));
        assertEquals("one", history.undo("two"));
        assertFalse(history.canUndo());
        assertEquals("two", history.redo("one"));
        assertEquals("three", history.redo("two"));
        assertFalse(history.canRedo());
    }

    @Test
    void thereIsNothingToUndoUntilSomethingIsRemembered() {
        var history = new EditHistory<String>(10);

        assertFalse(history.canUndo());
        assertNull(history.undo("one"));
        assertNull(history.redo("one"));
    }

    @Test
    void anEditMadeAfterAnUndoLeavesNothingToRedo() {
        var history = new EditHistory<String>(10);
        history.remember("one");
        history.remember("two");
        assertEquals("two", history.undo("three"));
        assertTrue(history.canRedo());

        history.remember("two again");

        assertFalse(history.canRedo(), "the path forward is not the one taken");
        assertEquals("two again", history.undo("something else"));
    }

    @Test
    void aForgottenHistoryHasNothingToUndoOrRedo() {
        EditHistory<String> history = new EditHistory<>(8);
        history.remember("a");
        history.remember("b");
        history.undo("c");

        history.forget();

        assertNull(history.undo("c"));
        assertNull(history.redo("c"));
    }

    @Test
    void onlySoManyStatesAreKept() {
        var history = new EditHistory<Integer>(3);
        for (int state = 1; state <= 5; state++) {
            history.remember(state);
        }

        assertEquals(5, history.undo(6));
        assertEquals(4, history.undo(5));
        assertEquals(3, history.undo(4));
        assertFalse(history.canUndo(), "the first two are long gone");
    }
}
