package io.groovebox.midi;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/**
 * Sends notes to an external MIDI device (e.g. a virtual MIDI cable feeding a
 * standalone synth like Surge XT) instead of the built-in Java synthesizer.
 * Devices are matched by a case-insensitive substring of their name — run
 * {@code java ListMidiDevices.java} to see what's available once a virtual cable is set up.
 */
public final class ExternalMidiOutput implements NoteOutput {
    private static final int ALL_SOUND_OFF = 120;
    private static final int ALL_NOTES_OFF = 123;

    private final MidiDevice device;
    private final NoteGate receiver;
    private final int channel;
    // every channel a note has gone out on, each of them silenced on the way out
    private final boolean[] used = new boolean[16];
    private final Thread shutdownHook;

    private ExternalMidiOutput(MidiDevice device, NoteGate receiver, int channel) {
        this.device = device;
        this.receiver = receiver;
        this.channel = channel;
        this.used[channel] = true;
        this.shutdownHook = new Thread(this::silence);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static ExternalMidiOutput open(String deviceNameContains, int channel, int program)
            throws MidiUnavailableException {
        MidiDevice device = findDevice(deviceNameContains);
        device.open();
        Receiver receiver;
        try {
            receiver = device.getReceiver();
            // a note left hanging by a run that was killed ends here, before anything new is played
            panic(receiver, channel);
            receiver.send(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0), -1);
        } catch (MidiUnavailableException exception) {
            device.close();
            throw exception;
        } catch (InvalidMidiDataException exception) {
            device.close();
            throw new IllegalStateException(exception);
        }
        return new ExternalMidiOutput(device, new NoteGate(receiver), channel);
    }

    /**
     * Silences every channel of a device, for a synth left with a note hanging by a run that was
     * killed rather than stopped - IntelliJ's Stop on a program run through Gradle ends the JVM
     * without running its shutdown hooks.
     */
    public static void panic(String deviceNameContains) throws MidiUnavailableException {
        MidiDevice device = findDevice(deviceNameContains);
        device.open();
        try (Receiver receiver = device.getReceiver()) {
            for (int channel = 0; channel < 16; channel++) {
                sendQuietly(receiver, ShortMessage.CONTROL_CHANGE, channel, ALL_SOUND_OFF);
                panic(receiver, channel);
            }
        } finally {
            device.close();
        }
    }

    private static MidiDevice findDevice(String nameContains) throws MidiUnavailableException {
        String needle = nameContains.toLowerCase();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            if (info.getName().toLowerCase().contains(needle)) {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device.getMaxReceivers() != 0) {
                    return device;
                }
            }
        }
        throw new MidiUnavailableException("No MIDI output device matching \"" + nameContains + "\"");
    }

    @Override
    public synchronized void noteOn(int pitch, int velocity) {
        noteOn(channel, pitch, velocity);
    }

    @Override
    public synchronized void noteOff(int pitch) {
        noteOff(channel, pitch);
    }

    /** A note on another channel than the one this output was opened on: one track's, of several. */
    public synchronized void noteOn(int toChannel, int pitch, int velocity) {
        used[toChannel] = true;
        send(ShortMessage.NOTE_ON, toChannel, pitch, velocity);
    }

    public synchronized void noteOff(int toChannel, int pitch) {
        send(ShortMessage.NOTE_OFF, toChannel, pitch, 0);
    }

    /** A control change on this output's channel - e.g. CC 74, which most synths map to filter cutoff. */
    public synchronized void controlChange(int controller, int value) {
        controlChange(channel, controller, value);
    }

    public synchronized void controlChange(int toChannel, int controller, int value) {
        send(ShortMessage.CONTROL_CHANGE, toChannel, controller, value);
    }

    /** The sound a channel plays with, as a MIDI file's track names it. */
    public synchronized void programChange(int toChannel, int program) {
        send(ShortMessage.PROGRAM_CHANGE, toChannel, program, 0);
    }

    private void send(int command, int toChannel, int data1, int data2) {
        try {
            receiver.send(new ShortMessage(command, toChannel, data1, data2), -1);
        } catch (InvalidMidiDataException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * CC 120 (All Sound Off) cuts every currently sounding note immediately, ignoring release
     * tails and sustain pedal — unlike CC 123 (All Notes Off), which just releases notes and can
     * still leave a synth ringing. Run on a JVM shutdown hook (see the constructor) so a killed
     * process (Ctrl+C, a crash) doesn't leave the external synth playing a stuck note forever.
     */
    private synchronized void allSoundOff(int onChannel) {
        try {
            send(ShortMessage.CONTROL_CHANGE, onChannel, ALL_SOUND_OFF, 0);
        } catch (RuntimeException exception) {
            // Best-effort: the device may already be gone (closed, unplugged) by the time this runs.
        }
    }

    /**
     * Everything that stops the synth, for close() and the shutdown hook alike. Note-ons are shut out
     * first: the JVM runs its shutdown hooks while the player's threads still run, and a note-on a
     * moment after All Sound Off would start a note that nothing ends. Then All Sound Off, and a
     * note-off for every pitch, for a synth that ignores All Sound Off.
     */
    private synchronized void silence() {
        receiver.shutNoteOns();
        for (int onChannel = 0; onChannel < used.length; onChannel++) {
            if (used[onChannel]) {
                allSoundOff(onChannel);
                panic(receiver, onChannel);
            }
        }
    }

    /** All Notes Off and a note-off for every pitch on one channel - best effort, like allSoundOff. */
    private static void panic(Receiver receiver, int channel) {
        sendQuietly(receiver, ShortMessage.CONTROL_CHANGE, channel, ALL_NOTES_OFF);
        for (int pitch = 0; pitch < 128; pitch++) {
            sendQuietly(receiver, ShortMessage.NOTE_OFF, channel, pitch);
        }
    }

    private static void sendQuietly(Receiver receiver, int command, int channel, int data1) {
        try {
            receiver.send(new ShortMessage(command, channel, data1, 0), -1);
        } catch (InvalidMidiDataException | RuntimeException deviceAlreadyGone) {
            // a device that is closed or unplugged has nothing left to silence
        }
    }

    @Override
    public synchronized void close() {
        silence();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException exception) {
            // The JVM is already shutting down (this close() call came from the hook itself, or a
            // sibling one) — nothing to remove.
        }
        receiver.close();
        device.close();
    }

    /**
     * The device's receiver, with a door for note-ons that closing shuts: after that only what ends a
     * note, or a control change, gets through.
     */
    static final class NoteGate implements Receiver {
        private final Receiver receiver;
        private volatile boolean noteOnsShut;

        NoteGate(Receiver receiver) {
            this.receiver = receiver;
        }

        void shutNoteOns() {
            noteOnsShut = true;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (noteOnsShut && message instanceof ShortMessage note && note.getCommand() == ShortMessage.NOTE_ON) {
                return;
            }
            receiver.send(message, timeStamp);
        }

        @Override
        public void close() {
            receiver.close();
        }
    }
}
