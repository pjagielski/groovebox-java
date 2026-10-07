package io.groovebox.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PatternCompiler {

    private PatternCompiler() {
    }

    /**
     * Compiles every track of a song into one loop's worth of notes. A {@link DrumTrack} is one
     * bar ({@code song.beatsPerBar()}) and repeats to fill {@link #totalBeats}, cut off where that
     * length ends - so a loop shorter than a bar plays only the start of the pattern; a
     * {@link MelodyTrack} already spans the full length and contributes once.
     */
    public static List<Note> compile(Song song) {
        double totalBeats = totalBeats(song);
        var notes = new ArrayList<Note>();
        for (Track track : song.tracks()) {
            notes.addAll(compileTrack(track, song.beatsPerBar(), totalBeats, track.gain()));
        }
        notes.sort(Comparator.comparingDouble(Note::beat));
        return List.copyOf(notes);
    }

    /**
     * One loop's worth of each track's notes, as {@link #compile} would give them but kept apart
     * and without the track's gain: for a player that applies the gain itself, as it plays, so a
     * fader or a mute is heard at once.
     */
    public static List<List<Note>> compileByTrack(Song song) {
        double totalBeats = totalBeats(song);
        return song.tracks().stream()
                .map(track -> compileTrack(track, song.beatsPerBar(), totalBeats, 1.0f))
                .toList();
    }

    private static List<Note> compileTrack(Track track, double beatsPerBar, double totalBeats, float gain) {
        return switch (track) {
            case DrumTrack drumTrack -> compileDrumTrack(drumTrack, beatsPerBar, totalBeats, gain);
            case MelodyTrack melodyTrack -> compileMelodyTrack(melodyTrack, gain);
            // a loop is audio, not notes: the engine plays it alongside what is compiled here
            case LoopTrack loop -> List.of();
        };
    }

    /**
     * The length of one loop of this song, in beats: the longest {@link MelodyTrack}, which is what
     * is written, or {@code beatsPerBar} for a pattern-only song — drum patterns tile to match it.
     * A {@link LoopTrack} sets it only when nothing is written to set it, so that adding a break or
     * giving one more bars never quietly makes the jam's own loop longer.
     */
    public static double totalBeats(Song song) {
        // what is written sets the length; a drum pattern is one bar and tiles to whatever it is
        double written = song.tracks().stream()
                .filter(track -> track instanceof MelodyTrack)
                .mapToDouble(track -> ((MelodyTrack) track).patternLengthBeats())
                .max()
                .orElse(0);
        if (written > 0) {
            return written;
        }
        // nothing written to set it, so a piece of audio sets it: a song of nothing but a break
        // loops with the break. Where something is written, a longer loop is cut off at the end of
        // the loop and starts again there, which is what the studio draws shaded.
        double audio = song.tracks().stream()
                .filter(track -> track instanceof LoopTrack)
                .mapToDouble(track -> ((LoopTrack) track).lengthBeats(song.beatsPerBar()))
                .max()
                .orElse(0);
        return audio > 0 ? audio : song.beatsPerBar();
    }

    private static List<Note> compileDrumTrack(DrumTrack track, double beatsPerBar, double totalBeats, float gain) {
        String steps = track.steps();
        double durationBeats = beatsPerBar / steps.length();
        // ceil, not round: a loop that ends mid-bar still needs that bar's opening steps
        int repeats = Math.max(1, (int) Math.ceil(totalBeats / beatsPerBar));
        var notes = new ArrayList<Note>();
        for (int repeat = 0; repeat < repeats; repeat++) {
            double barOffset = repeat * beatsPerBar;
            for (int step = 0; step < steps.length(); step++) {
                float accent = accentOf(steps.charAt(step));
                double beat = barOffset + (double) step * beatsPerBar / steps.length();
                if (accent > 0.0f && beat < totalBeats) {
                    notes.add(new Note(beat, track.drum(), durationBeats, gain * accent));
                }
            }
        }
        return notes;
    }

    private static List<Note> compileMelodyTrack(MelodyTrack track, float gain) {
        return track.notes().stream()
                .map(note -> new Note(note.beat(), note.voice(), note.durationBeats(), note.velocity() * gain,
                        note.envelope()))
                .toList();
    }

    /** 'X' is 1.0, 'x' 0.8, 'o' 0.5; '.', '-' and ' ' are rests. */
    public static float accentOf(char step) {
        return switch (step) {
            case 'X' -> 1.0f;
            case 'x' -> 0.8f;
            case 'o' -> 0.5f;
            case '.', '-', ' ' -> 0.0f;
            default -> throw new IllegalArgumentException("Unknown pattern character: " + step);
        };
    }
}
