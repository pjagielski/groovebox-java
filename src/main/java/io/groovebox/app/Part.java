package io.groovebox.app;

/**
 * What a track chip selects, and so what the screen, the pads and the knobs are about: one row of
 * the drum grid, one melody track, or one loop track.
 */
sealed interface Part {

    /** A row of the drum grid, by its place in the grid. */
    record DrumRow(int row) implements Part {
    }

    /** A melody track, by its place in the session's track list. */
    record Melody(int track) implements Part {
    }

    /** A loop track, by its place in the session's track list. */
    record Loop(int track) implements Part {
    }
}
