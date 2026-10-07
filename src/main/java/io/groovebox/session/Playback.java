package io.groovebox.session;

import io.groovebox.audio.AudioEngine;
import io.groovebox.audio.NoteListener;
import io.groovebox.midi.ExternalMidiOutput;

import javax.sound.sampled.LineUnavailableException;
import java.util.function.Supplier;

/**
 * Play and stop for a jam, and the audio device behind them. Playing takes the device; stopping
 * lets the last notes, repeats and reverb die away on their own rather than cutting them. While
 * the jam is stopped, a sound asked for — a key of the roll, a slice — goes through a session that
 * plays no loop, opened at the first such sound, so nothing holds the device until someone wants
 * to hear something.
 *
 * <p>Also where the tracks that go out to an external synth are sent: whatever device is connected
 * when a note is due, or nowhere. Connecting and disconnecting happen while the jam plays.
 */
public final class Playback {
    private final AudioEngine engine;
    private final Supplier<AudioEngine.Jam> jam;
    // the jam playing; null while it is stopped
    private AudioEngine.LiveSession live;
    // the jam last stopped, its tails left to die away until the next one starts
    private AudioEngine.LiveSession ringing;
    // a session that plays no loop, for sounds asked for while the jam is stopped
    private AudioEngine.LiveSession idle;
    // read by the thread that sends notes out as well as set here, so always the output as it is now
    private volatile ExternalMidiOutput midi;
    private double externalLatencyMillis;

    public Playback(AudioEngine engine, Supplier<AudioEngine.Jam> jam) {
        this.engine = engine;
        this.jam = jam;
    }

    public boolean playing() {
        return live != null;
    }

    /** The jam as it plays, for where it is and for the effects played over it; null while stopped. */
    public AudioEngine.LiveSession live() {
        return live;
    }

    /** Starts the jam from its first beat; nothing happens if it is already playing. */
    public void play() throws LineUnavailableException {
        if (live != null) {
            return;
        }
        if (ringing != null) {
            // a tail from the last stop is still sounding; it makes way for the new jam
            ringing.close();
            ringing = null;
        }
        if (idle != null) {
            // the jam is about to take the audio device; sounds go through its own session from now on
            idle.close();
            idle = null;
        }
        live = engine.playLive(jam, toMidi());
        live.setExternalLatencyMillis(externalLatencyMillis);
    }

    /** The jam stops, but its last notes, repeats and reverb are left to die away on their own. */
    public void stop() {
        if (live == null) {
            return;
        }
        live.release();
        ringing = live;
        live = null;
    }

    /**
     * What a sound asked for is heard through: the jam's own session while it plays, or one that
     * plays no loop, opened now and kept until the jam starts.
     */
    public AudioEngine.LiveSession listening() throws LineUnavailableException {
        if (live != null) {
            return live;
        }
        if (idle == null) {
            idle = engine.openIdle(jam);
        }
        return idle;
    }

    /**
     * The drums and loops of the jam as it is now, heard at once rather than from the next loop:
     * for a pad or a knob, which a hand plays rather than writes. Nothing while stopped, when the
     * next Play starts from the jam as it is anyway.
     */
    public void takeNow() {
        if (live != null) {
            live.takeNow();
        }
    }

    /** How far ahead of the beat notes go out, so an external synth sounds with the drums. */
    public void setExternalLatencyMillis(double millis) {
        externalLatencyMillis = millis;
        if (live != null) {
            live.setExternalLatencyMillis(millis);
        }
    }

    /** The external synth's device, or null while none is connected. */
    public ExternalMidiOutput midi() {
        return midi;
    }

    /** Opens the first MIDI device whose name has {@code device} in it, in place of any open now. */
    public ExternalMidiOutput connect(String device, int channel) throws Exception {
        ExternalMidiOutput next = ExternalMidiOutput.open(device, channel, 0);
        ExternalMidiOutput before = midi;
        midi = next;
        if (before != null) {
            before.close();
        }
        return next;
    }

    public void disconnect() {
        ExternalMidiOutput output = midi;
        midi = null;
        if (output != null) {
            output.close();
        }
    }

    /** Everything let go of: the jam, its tail, the idle session and the MIDI device. */
    public void close() {
        stop();
        if (ringing != null) {
            ringing.close();
            ringing = null;
        }
        if (idle != null) {
            idle.close();
            idle = null;
        }
        disconnect();
    }

    private NoteListener toMidi() {
        return new NoteListener() {
            @Override
            public void noteOn(int toChannel, int midiNote, int velocity) {
                ExternalMidiOutput output = midi;
                if (output != null) {
                    output.noteOn(toChannel, midiNote, velocity);
                }
            }

            @Override
            public void noteOff(int toChannel, int midiNote) {
                ExternalMidiOutput output = midi;
                if (output != null) {
                    output.noteOff(toChannel, midiNote);
                }
            }
        };
    }
}
