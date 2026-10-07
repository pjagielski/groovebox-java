package io.groovebox.session;

import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Voice;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MelodyWindowTest {

    private static final int TICKS = 480;

    /** A sequence with one note per entry: {startBeat, beats held, pitch}. */
    private static Sequence with(double[][] notes) throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, TICKS);
        Track track = sequence.createTrack();
        for (double[] note : notes) {
            int pitch = (int) note[2];
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, 100),
                    Math.round(note[0] * TICKS)));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0),
                    Math.round((note[0] + note[1]) * TICKS)));
        }
        return sequence;
    }

    @Test
    void aNoteHeldPastTheEndOfTheWindowIsCutThere() throws Exception {
        // the last note of a file is often held for bars; two bars taken out of it must not carry
        // that into the next pass of the loop
        Sequence sequence = with(new double[][] {{0, 1, 60}, {7, 12, 64}});

        MelodyTrack window = Sounds.loadMelodyTrack(sequence, 0, 0, 2);

        assertEquals(8.0, window.patternLengthBeats());
        Note held = window.notes().stream()
                .filter(note -> ((Voice.Pitch) note.voice()).midiNote() == 64)
                .findFirst().orElseThrow();
        assertEquals(1.0, held.durationBeats(), 1e-9, "cut at the end of the loop, not twelve beats long");
        assertTrue(held.beat() + held.durationBeats() <= 8.0);
    }

    @Test
    void aNoteThatFitsIsLeftAsItIs() throws Exception {
        Sequence sequence = with(new double[][] {{2, 1.5, 60}});

        MelodyTrack window = Sounds.loadMelodyTrack(sequence, 0, 0, 2);

        assertEquals(1.5, window.notes().getFirst().durationBeats(), 1e-9);
    }

    @Test
    void aWindowFurtherInCutsAtItsOwnEnd() throws Exception {
        // bars three and four of a file whose note at bar four runs to the end of it
        Sequence sequence = with(new double[][] {{12, 9, 55}});

        MelodyTrack window = Sounds.loadMelodyTrack(sequence, 0, 2, 2);

        Note only = window.notes().getFirst();
        assertEquals(4.0, only.beat(), 1e-9, "a beat into the window");
        assertEquals(4.0, only.durationBeats(), 1e-9, "and held to its end, not past it");
    }
}
