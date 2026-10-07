package io.groovebox.session;

import io.groovebox.livecode.LiveCode;
import io.groovebox.model.Drum;
import io.groovebox.model.DrumTrack;
import io.groovebox.model.Envelope;
import io.groovebox.model.Note;
import io.groovebox.model.PatternCompiler;
import io.groovebox.synth.LiveEffects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the studio's grid: a drum, the accent of each step across one bar (0 for a rest), the
 * row's gain, how long each hit's note lasts in bars (what {@code .rel} counts from) and how its
 * hits are shaped (decayed, released, tuned). A preset's {@link DrumTrack} and live code are both
 * drawn into rows, and the rows are what plays.
 *
 * <p>Two things a hand sets for the whole row rather than a step. {@code accentDepth} is how far
 * apart its accents are heard: at 0.5 a step is as loud as its accent says, towards 0 every hit
 * comes out alike, towards 1 the soft ones fall away under the hard ones. And {@code channel} is
 * the row's own effects, which it is heard through as a loop is through its own: a row of the
 * kit with drive and echo the others do not have.
 */
public record GridRow(String label, Drum drum, float[] accents, float gain, double noteBars, Envelope envelope,
                      float accentDepth, LiveEffects channel) {

    /** Where accents are heard as written: the depth a row starts at. */
    public static final float WRITTEN_ACCENTS = 0.5f;
    private static final int MAX_STEPS = 64;
    private static final double EPSILON = 1e-6;

    /** A row with its accents heard as written, and a channel of its own with nothing turned up. */
    public GridRow(String label, Drum drum, float[] accents, float gain, double noteBars, Envelope envelope) {
        this(label, drum, accents, gain, noteBars, envelope, WRITTEN_ACCENTS, new LiveEffects());
    }

    public static GridRow fromTrack(DrumTrack track) {
        String steps = track.steps();
        float[] accents = new float[steps.length()];
        for (int step = 0; step < accents.length; step++) {
            accents[step] = PatternCompiler.accentOf(steps.charAt(step));
        }
        return new GridRow(nameOf(track.drum()), track.drum(), accents, track.gain(), 1.0 / accents.length,
                Envelope.NONE);
    }

    /** One row per sound in each layer of the code, with as many steps as it takes to hold every hit exactly. */
    public static List<GridRow> fromCode(LiveCode code) {
        Map<Key, List<LiveCode.Hit>> groups = new LinkedHashMap<>();
        for (LiveCode.Hit hit : code.hits()) {
            groups.computeIfAbsent(new Key(hit.layer(), hit.drum()), key -> new ArrayList<>()).add(hit);
        }
        List<GridRow> rows = new ArrayList<>();
        for (List<LiveCode.Hit> hits : groups.values()) {
            int steps = stepsFor(hits);
            float[] accents = new float[steps];
            for (LiveCode.Hit hit : hits) {
                int step = (int) Math.round(hit.start() * steps) % steps;
                accents[step] = Math.max(accents[step], hit.velocity());
            }
            LiveCode.Hit first = hits.get(0);
            rows.add(new GridRow((first.layer() + 1) + " " + nameOf(first.drum()), first.drum(), accents, 1.0f,
                    first.duration(), first.envelope()));
        }
        return List.copyOf(rows);
    }

    /**
     * Every row's notes over {@code lengthBeats}, one bar after another, cut off where the length
     * ends, each at its row's level: the grid heard as one track.
     */
    public static List<Note> notes(List<GridRow> rows, double beatsPerBar, double lengthBeats) {
        List<Note> notes = new ArrayList<>();
        for (GridRow row : rows) {
            for (Note note : row.notes(beatsPerBar, lengthBeats)) {
                notes.add(new Note(note.beat(), note.voice(), note.durationBeats(),
                        Math.min(1.0f, note.velocity() * row.gain()), note.envelope()));
            }
        }
        notes.sort(Comparator.comparingDouble(Note::beat));
        return List.copyOf(notes);
    }

    /**
     * This row's notes over {@code lengthBeats}, one bar after another, cut off where the length
     * ends. Without the row's level, which is its track's gain when it is a track of its own: a
     * gain the engine takes up at once, where a note's velocity waits for the next loop.
     */
    public List<Note> notes(double beatsPerBar, double lengthBeats) {
        List<Note> notes = new ArrayList<>();
        int bars = (int) Math.ceil(lengthBeats / beatsPerBar);
        int steps = accents.length;
        for (int bar = 0; bar < bars; bar++) {
            for (int step = 0; step < steps; step++) {
                float velocity = heard(accents[step]);
                double beat = (bar + (double) step / steps) * beatsPerBar;
                if (velocity > 0.0f && beat < lengthBeats) {
                    notes.add(new Note(beat, drum, noteBars * beatsPerBar, velocity, envelope));
                }
            }
        }
        return List.copyOf(notes);
    }

    /**
     * How loud a step of {@code accent} is heard, before the row's level: the accent bent by the
     * row's depth. The bend is a power, so a full hit stays full and a rest stays a rest whatever
     * the depth, and at {@link #WRITTEN_ACCENTS} the accent is left as it is.
     */
    float heard(float accent) {
        if (accent <= 0.0f) {
            return 0.0f;
        }
        return Math.min(1.0f, (float) Math.pow(accent, 2.0 * accentDepth));
    }

    /** The same hits, heard {@code next} times as loud: the row's level rather than any one step's. */
    public GridRow withGain(float next) {
        return new GridRow(label, drum, accents, next, noteBars, envelope, accentDepth, channel);
    }

    public GridRow withAccent(int step, float accent) {
        float[] changed = accents.clone();
        changed[step] = accent;
        return new GridRow(label, drum, changed, gain, noteBars, envelope, accentDepth, channel);
    }

    /**
     * These steps with the sound a hand gave {@code before}: its channel, its accents' depth and
     * its tuning. What a row of code says - its steps, gain, decay and release - stays the code's.
     */
    public GridRow soundingAs(GridRow before) {
        return new GridRow(label, drum, accents, gain, noteBars, envelope.tunedTo(before.envelope().semitones()),
                before.accentDepth(), before.channel());
    }

    /** The same steps with their accents {@code depth} apart: see {@link #accentDepth}. */
    public GridRow withAccentDepth(float depth) {
        return new GridRow(label, drum, accents, gain, noteBars, envelope, Math.max(0, Math.min(1, depth)),
                channel);
    }

    /** Each hit dying away over {@code seconds}; 0 lets the sample ring as it was recorded. */
    public GridRow withDecay(double seconds) {
        return new GridRow(label, drum, accents, gain, noteBars, envelope.withDecay(Math.max(0, seconds)),
                accentDepth, channel);
    }

    /** Each hit {@code semitones} up or down from the sample as recorded. */
    public GridRow tunedTo(double semitones) {
        return new GridRow(label, drum, accents, gain, noteBars, envelope.tunedTo(semitones), accentDepth,
                channel);
    }

    /** The fewest steps that put every hit exactly on one, widened to 16 whenever that still fits. */
    private static int stepsFor(List<LiveCode.Hit> hits) {
        for (int steps = 1; steps <= MAX_STEPS; steps++) {
            int candidate = steps;
            boolean fits = hits.stream()
                    .allMatch(hit -> Math.abs(hit.start() * candidate - Math.rint(hit.start() * candidate)) < EPSILON);
            if (fits) {
                return 16 % candidate == 0 ? 16 : candidate;
            }
        }
        return MAX_STEPS;
    }

    private static String nameOf(Drum drum) {
        return LiveCode.soundName(drum);
    }

    private record Key(int layer, Drum drum) {
    }
}
