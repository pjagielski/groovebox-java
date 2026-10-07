package io.groovebox.midi;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalMidiOutputGateTest {

    @Test
    void noNoteOnGetsOutOnceClosingHasBegun() throws Exception {
        List<String> sent = new ArrayList<>();
        Receiver recording = new Receiver() {
            @Override
            public void send(MidiMessage message, long timeStamp) {
                ShortMessage note = (ShortMessage) message;
                sent.add(note.getCommand() + " " + note.getData1());
            }

            @Override
            public void close() {
            }
        };
        var gate = new ExternalMidiOutput.NoteGate(recording);

        gate.send(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), -1);
        gate.shutNoteOns();
        // the player's threads keep running while the JVM shuts down
        gate.send(new ShortMessage(ShortMessage.NOTE_ON, 0, 64, 100), -1);
        gate.send(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), -1);
        gate.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 120, 0), -1);

        assertEquals(List.of(ShortMessage.NOTE_ON + " 60", ShortMessage.NOTE_OFF + " 60",
                ShortMessage.CONTROL_CHANGE + " 120"), sent);
    }
}
