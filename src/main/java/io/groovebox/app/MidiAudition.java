package io.groovebox.app;

import io.groovebox.audio.AudioEngine;
import io.groovebox.audio.SampleBank;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Song;
import io.groovebox.session.Session;
import io.groovebox.synth.PitchSynth;

import javax.sound.sampled.LineUnavailableException;
import java.util.List;
import java.util.Map;

/** An isolated audition of a MIDI range, stopped by the dialog at its end or when the selection changes. */
final class MidiAudition implements AutoCloseable {
    private final AudioEngine engine = new AudioEngine(SampleBank.from(Map.of()));
    private AudioEngine.LiveSession live;

    void start(List<Note> notes, double bpm, double beats, PitchSynth synth) throws LineUnavailableException {
        close();
        // A silent bar after the phrase gives the UI time to stop at its audible end before it repeats.
        Song song = new Song(bpm, Session.BEATS_PER_BAR,
                List.of(new MelodyTrack(notes, beats + Session.BEATS_PER_BAR, 1)));
        AudioEngine.Jam jam = new AudioEngine.Jam(song, synth);
        live = engine.playLive(() -> jam, null);
    }

    double beat() {
        var position = live == null ? null : live.position();
        return position == null ? -1 : position.beat();
    }

    boolean playing() {
        return live != null;
    }

    Throwable failure() {
        return live == null ? null : live.failure().orElse(null);
    }

    @Override
    public void close() {
        if (live != null) {
            live.close();
            live = null;
        }
    }
}
