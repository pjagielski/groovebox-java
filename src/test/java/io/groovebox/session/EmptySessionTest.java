package io.groovebox.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmptySessionTest {
    @Test
    void aFreshWorkspaceHasOnlyAnEditableSilentKit() {
        Session session = new Session();
        session.startEmpty();

        assertEquals(1, session.tracks().size());
        assertInstanceOf(StudioTrack.Drums.class, session.tracks().selected());
        assertFalse(session.rows().isEmpty());
        assertTrue(GridRow.notes(session.rows(), Session.BEATS_PER_BAR, 32).isEmpty());
        assertNull(session.tracks().firstMelody());
        assertEquals(120, session.bpm());
        assertEquals(8, session.bars());
        assertFalse(session.undo());
    }

    @Test
    void demoCanStillBeLoadedAndThenClearedToAFreshWorkspace() {
        Session session = new Session();
        session.startEmpty();
        DemoJam.startIn(session);
        assertEquals(4, session.tracks().size());
        assertFalse(GridRow.notes(session.rows(), Session.BEATS_PER_BAR, 32).isEmpty());

        session.setBpm(93);
        session.setBars(2);
        session.startEmpty();

        assertEquals(1, session.tracks().size());
        assertEquals("", session.chords());
        assertEquals("", session.code());
        assertNull(session.config());
        assertEquals(120, session.bpm());
        assertEquals(8, session.bars());
        assertFalse(session.undo());
    }
}
