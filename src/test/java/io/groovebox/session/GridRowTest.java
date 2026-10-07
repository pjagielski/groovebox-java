package io.groovebox.session;

import io.groovebox.livecode.LiveCode;
import io.groovebox.model.Drum;
import io.groovebox.model.DrumTrack;
import io.groovebox.model.Envelope;
import io.groovebox.model.Note;
import io.groovebox.model.PatternCompiler;
import io.groovebox.model.Song;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GridRowTest {

    private static final String EXAMPLE = """
            $: stack(
                s("bd(3,8,5)"),
                s("[~ sd]*2").gain(1.25),
                s("hh*16").gain("[0.2 0.1]*8"),
                s("[~ oh ~ oh]*2").gain(0.45).rel(0.1).dec(0.2)
              )
            """;

    @Test
    void theAccentDepthBendsTheSoftHitsAndLeavesTheFullOnesFull() {
        GridRow row = new GridRow("bd", Drum.KICK, new float[] {1.0f, 0.8f, 0.5f, 0.0f}, 1.0f, 0.25,
                Envelope.NONE);

        assertEquals(List.of(1.0f, 0.8f, 0.5f), velocities(row), "as written");
        assertEquals(List.of(1.0f, 1.0f, 1.0f), velocities(row.withAccentDepth(0)), "all alike");
        List<Float> deep = velocities(row.withAccentDepth(1));
        assertEquals(1.0f, deep.get(0), "the soft ones fall away");
        assertEquals(0.64f, deep.get(1), 1e-6f);
        assertEquals(0.25f, deep.get(2), 1e-6f);
        assertEquals(List.of(1.0f, 0.8f, 0.5f), velocities(row.withGain(0.5f)), "the level is the track's gain");
        assertEquals(List.of(0.5f, 0.4f, 0.25f), GridRow.notes(List.of(row.withGain(0.5f)), 4, 4).stream()
                .map(Note::velocity).toList(), "but the grid heard as one track has it in its notes");
    }

    @Test
    void aRowDrawnAgainFromCodeKeepsTheSoundAHandGaveItButTheCodesSteps() {
        GridRow before = new GridRow("1 bd", Drum.KICK, new float[] {1, 0, 0, 0}, 1.0f, 0.25,
                new Envelope(0.3, 0.0, 0.0)).withAccentDepth(0.9f).tunedTo(-3);
        GridRow drawn = new GridRow("1 bd", Drum.KICK, new float[] {1, 0, 1, 0}, 0.8f, 0.25,
                new Envelope(0.1, 0.0));

        GridRow kept = drawn.soundingAs(before);

        assertSame(before.channel(), kept.channel());
        assertEquals(0.9f, kept.accentDepth());
        assertEquals(new Envelope(0.1, 0.0, -3), kept.envelope(), "the code's decay, the hand's tuning");
        assertEquals(0.8f, kept.gain());
        assertArrayEquals(drawn.accents(), kept.accents());
    }

    private static List<Float> velocities(GridRow row) {
        return row.notes(4, 4).stream().map(Note::velocity).toList();
    }

    @Test
    void drawsEachLayerOfTheCodeAsARowOfSixteenSteps() {
        List<GridRow> rows = GridRow.fromCode(LiveCode.parse(EXAMPLE));

        assertEquals(List.of("1 bd", "2 sd", "3 hh", "4 oh"), rows.stream().map(GridRow::label).toList());
        assertArrayEquals(accents(16, 1.0f, 2, 6, 12), rows.get(0).accents());
        assertArrayEquals(accents(16, 1.0f, 4, 12), rows.get(1).accents());
        float[] hats = new float[16];
        for (int step = 0; step < 16; step++) {
            hats[step] = step % 2 == 0 ? 0.2f : 0.1f;
        }
        assertArrayEquals(hats, rows.get(2).accents());
        assertArrayEquals(accents(16, 0.45f, 2, 6, 10, 14), rows.get(3).accents());
        assertEquals(new Envelope(0.2, 0.1), rows.get(3).envelope());
    }

    @Test
    void theGridPlaysExactlyWhatTheCodeWouldHavePlayed() {
        LiveCode code = LiveCode.parse(EXAMPLE);

        assertEquals(code.notes(4.0, 8.0), GridRow.notes(GridRow.fromCode(code), 4.0, 8.0));
    }

    @Test
    void aSubdivisionThatDoesNotFitSixteenStepsGetsItsOwnStepCount() {
        List<GridRow> rows = GridRow.fromCode(LiveCode.parse("s(\"bd [sd sd sd]\")"));

        assertEquals(16, rows.get(0).accents().length);
        assertArrayEquals(accents(6, 1.0f, 3, 4, 5), rows.get(1).accents());
    }

    @Test
    void aPresetRowPlaysLikeTheDrumTrackItCameFrom() {
        DrumTrack track = new DrumTrack(Drum.KICK, "X.o.x...", 0.5f);

        assertEquals(PatternCompiler.compile(new Song(120, 4, List.of(track))),
                GridRow.notes(List.of(GridRow.fromTrack(track)), 4.0, 4.0));
    }

    @Test
    void changingOneStepLeavesTheOriginalRowAlone() {
        GridRow row = GridRow.fromTrack(new DrumTrack(Drum.KICK, "X...", 1.0f));

        GridRow changed = row.withAccent(1, 0.8f);

        assertArrayEquals(new float[]{1.0f, 0.8f, 0.0f, 0.0f}, changed.accents());
        assertArrayEquals(new float[]{1.0f, 0.0f, 0.0f, 0.0f}, row.accents());
    }

    private static float[] accents(int steps, float accent, int... hits) {
        float[] accents = new float[steps];
        for (int hit : hits) {
            accents[hit] = accent;
        }
        return accents;
    }
}
