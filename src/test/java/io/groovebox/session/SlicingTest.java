package io.groovebox.session;

import io.groovebox.audio.Sample;
import io.groovebox.model.Step;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlicingTest {

    private static final Sample AUDIO = Sample.mono(new float[4_000]);

    /** Four slices of a beat each over a bar, on the grid: 0, 1000, 2000, 3000. */
    private static StudioTrack.Slicing cut() {
        return StudioTrack.Slicing.of(AUDIO, 1, false, 4, List.of());
    }

    @Test
    void aFreshCutPlaysTheSlicesInTheOrderTheyWereRecordedIn() {
        StudioTrack.Slicing slicing = cut();

        assertEquals(4, slicing.count());
        assertEquals(List.of(0, 1_000, 2_000, 3_000), slicing.starts());
        assertEquals(List.of(0, 1, 2, 3), slicing.order());
        assertTrue(slicing.straight());
    }

    @Test
    void aLoopPlayedAsRecordedIsOnePieceOfAudioRatherThanASliceAStep() {
        // nothing for the engine to lay out: it strikes the whole thing once, as it always did
        assertEquals(List.of(), cut().steps(4_000));
    }

    @Test
    void aRearrangedPassNamesThePieceStruckAtEveryStep() {
        StudioTrack.Slicing slicing = cut().with(0, 3).with(2, -1);

        assertFalse(slicing.straight());
        assertEquals(List.of(
                new Step(3_000, 4_000),
                new Step(1_000, 2_000),
                Step.REST,
                new Step(3_000, 4_000)), slicing.steps(4_000));
    }

    @Test
    void aCutIntoFewerSlicesKeepsWhatStillFitsAndStraightensTheRest() {
        StudioTrack.Slicing four = cut().with(0, 3).with(1, 0);

        // half as many slices: step 0 named slice 3, which is no longer there, so it names its own
        StudioTrack.Slicing two = StudioTrack.Slicing.of(AUDIO, 2, false, 4, four.order());

        assertEquals(2, two.count());
        assertEquals(List.of(0, 0), two.order());
    }

    @Test
    void straighteningPutsTheSlicesBackInOrder() {
        assertTrue(cut().with(0, 3).straightened().straight());
    }
}
