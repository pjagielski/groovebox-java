package io.groovebox.livecode;

import io.groovebox.model.Drum;
import io.groovebox.model.Envelope;
import io.groovebox.model.Note;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiveCodeTest {

    private static final String EXAMPLE = """
            $: stack(
                s("bd(3,8,5)"),
                s("[~  sd]*2").gain(1.25),
                s("hh*16").gain("[0.2 0.1]*8"),
                s("[~ oh ~ oh]*2").gain(0.45).rel(0.1).dec(0.2)
              )
            """;

    @Test
    void compilesEveryLayerOfTheExampleIntoOneBar() {
        List<Note> notes = LiveCode.parse(EXAMPLE).notes(4.0, 4.0);

        assertEquals(List.of(0.5, 1.5, 3.0), beats(notes, Drum.KICK));
        assertEquals(List.of(1.0, 3.0), beats(notes, Drum.SNARE));
        assertEquals(List.of(1.0f, 1.0f), velocities(notes, Drum.SNARE));
        assertEquals(IntStream.range(0, 16).mapToObj(step -> step * 0.25).toList(), beats(notes, Drum.CLOSED_HAT));
        assertEquals(IntStream.range(0, 16).mapToObj(step -> step % 2 == 0 ? 0.2f : 0.1f).toList(),
                velocities(notes, Drum.CLOSED_HAT));
        assertEquals(List.of(0.5, 1.5, 2.5, 3.5), beats(notes, Drum.OPEN_HAT));
        notes.stream().filter(note -> note.voice() == Drum.OPEN_HAT).forEach(note -> {
            assertEquals(0.45f, note.velocity());
            assertEquals(new Envelope(0.2, 0.1), note.envelope());
        });
    }

    @Test
    void repeatsTheCycleOverTheLoopAndCutsItWhereTheLoopEnds() {
        var code = LiveCode.parse("s(\"bd*2\")");

        assertEquals(List.of(0.0, 2.0, 4.0, 6.0), beats(code.notes(4.0, 8.0), Drum.KICK));
        assertEquals(List.of(0.0), beats(code.notes(4.0, 1.0), Drum.KICK));
    }

    @Test
    void aCommentedOutBlockIsSilentAndTheOthersPlayTogether() {
        var code = LiveCode.parse("""
                $: s("bd*4")
                // $: s("sd*4")
                $: s("hh*2")
                """);

        List<Note> notes = code.notes(4.0, 4.0);

        assertEquals(4, beats(notes, Drum.KICK).size());
        assertEquals(List.of(), beats(notes, Drum.SNARE));
        assertEquals(2, beats(notes, Drum.CLOSED_HAT).size());
    }

    @Test
    void aMissingCommaIsReportedWhereTheNextLayerStarts() {
        String source = "$: stack(\n  s(\"bd\")\n  s(\"sd\")\n)";

        var error = assertThrows(LiveCodeException.class, () -> LiveCode.parse(source));

        assertEquals("line 3, column 3: expected ',' or ')'", error.describe(source));
    }

    @Test
    void anUnknownSoundListsTheOnesThatExist() {
        var error = assertThrows(LiveCodeException.class, () -> LiveCode.parse("s(\"bd kick\")"));

        assertEquals("unknown sound 'kick', expected one of [bd, cp, hh, oh, sd]", error.getMessage());
    }

    private static List<Double> beats(List<Note> notes, Drum drum) {
        return notes.stream().filter(note -> note.voice() == drum).map(Note::beat).toList();
    }

    private static List<Float> velocities(List<Note> notes, Drum drum) {
        return notes.stream().filter(note -> note.voice() == drum).map(Note::velocity).toList();
    }
}
