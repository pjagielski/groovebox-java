package io.groovebox.session;

import io.groovebox.model.Note;
import io.groovebox.model.Voice;
import io.groovebox.synth.DelaySync;
import io.groovebox.synth.EffectParams;
import io.groovebox.synth.SynthParams;
import io.groovebox.synth.SynthSetting;
import io.groovebox.synth.Waveform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The optional demo loaded from the box's title bar: Am9 Fmaj9 Dm9 E7♭9, a bar each and
 * twice round, under a plucked lead echoing on the dotted eighth, a pad holding the chords and
 * pumping under the kick, a sub bass on the off-beats, and the disco pattern. Written here as
 * notes, so the box sounds at once without a file of any kind - and played rather than typed: the
 * hands are uneven and the off-beat sixteenths a little late.
 *
 * <p>The lead is a tune over a pedal rather than an arpeggio: a line on the accents of a 3+3+2
 * rhythm, twice in a bar, and the chord's root ticking quietly on the sixteenths between them.
 * The first time round the chords the line asks, climbing; the second time it answers, coming
 * down from where the first left it, and its last bar drops the pedal and runs up the E7♭9 alone
 * to throw the loop back to its start. The line is written high and played {@link #LOWERED}
 * notes of its chord lower - a few inversions down rather than an octave - so it keeps its shape
 * and sits in the middle of the keyboard, the pedal an octave under it.
 *
 * <p>The ninths are what keep it from the four chords everybody knows: the chords share so many
 * notes that the pad hardly moves while the bass walks under it, and the E's flat ninth is the F
 * the Dm9 had, its third the G sharp that pulls back to A when the loop comes round.
 */
public final class DemoJam {
    public static final String LEAD_SYNTH = "pluck";
    static final String PAD_SYNTH = "pad";
    static final String BASS_SYNTH = "sub";
    static final String DRUMS = "disco";
    static final double BPM = 120;
    static final int BARS = 8;
    static final int SIXTEENTHS = 16;

    // each chord, five notes from the root up, in MIDI numbers: what the lead's pedal and line are made of
    static final int[][] CHORDS = {
            {69, 72, 76, 79, 83}, // Am9: A C E G B
            {65, 69, 72, 76, 79}, // Fmaj9: F A C E G
            {62, 65, 69, 72, 76}, // Dm9: D F A C E
            {64, 68, 71, 74, 77}, // E7♭9: E G♯ B D F
    };
    // the same chords for the pad, voiced so that no voice moves more than a whole tone to the next,
    // round the loop too: two notes change from Am9 to Fmaj9, two to Dm9, two to E7♭9 and three
    // back to Am9
    private static final int[][] PAD_CHORDS = {
            {52, 55, 57, 59}, // E G A B
            {52, 55, 57, 60}, // E G A C
            {50, 53, 57, 60}, // D F A C
            {50, 53, 56, 59}, // D F G♯ B
    };
    // each bar's root, for the bass: as low as a sub bass still carries, A1 F1 D2 E1
    private static final int[] ROOTS = {33, 29, 38, 28};
    // the last bass note before the chords come round: G sharp, the E chord's third and a semitone under A
    private static final int LEADING_NOTE = 32;

    // the sixteenths of a bar the line falls on: 3+3+2, twice
    static final int[] ACCENTS = {0, 3, 6, 8, 11, 14};
    // the sixteenths the pedal ticks on: the rest of the bar but its last, which breathes before the next
    private static final int[] PEDAL = {1, 2, 4, 5, 7, 9, 10, 12, 13};
    // the line as written, a note an accent, a bar a row: four bars asking and four answering
    static final int[][] LINE = {
            {76, 79, 83, 81, 79, 76}, // Am9    E G B A G E
            {76, 79, 81, 79, 76, 72}, // Fmaj9  E G A G E C
            {77, 76, 72, 74, 76, 77}, // Dm9    F E C D E F
            {80, 77, 74, 71, 74, 77}, // E7♭9   G♯ F D B D F
            {83, 81, 79, 76, 79, 81}, // Am9    B A G E G A
            {84, 81, 79, 76, 77, 79}, // Fmaj9  C A G E F G
            {81, 77, 76, 72, 74, 76}, // Dm9    A F E C D E
            {71, 74, 77, 80, 83, 86}, // E7♭9   B D F G♯ B D, alone
    };
    /**
     * How many notes of its chord the line is played below where it is written: five would be an
     * octave, three is a few inversions, about a fifth or a sixth down.
     */
    static final int LOWERED = 3;
    // how much later than the grid an off-beat sixteenth lands, in beats: about 10 ms here
    static final double SWING = 0.02;
    // how long the line's notes and the pedal's sound, in beats: the line rings, the pedal is a tick
    static final double SUNG = 0.22;
    static final double TICK = 0.08;

    private DemoJam() {
    }

    /** Starts {@code session} on the demo: the drums, the lead, the pad and the bass, nothing to undo. */
    public static void startIn(Session session) {
        // the patches as they were turned on the box by ear, not as they come
        Instrument lead = Instrument.of(LEAD_SYNTH);
        lead.setMidi(0, 74, 64);
        // three squares rather than seven saws, a resonant filter opened less far by each pick: a
        // hollower, more vocal pluck; the echo on the dotted eighth lands between the sixteenths
        // and turns them into a running line
        tune(lead, DelaySync.DOTTED_EIGHTH, params -> params
                        .withWaveform(Waveform.SQUARE).withVoices(3).withDetune(21.2f)
                        .withCutoff(829.8f).withResonance(0.70f).withFilterEnvAmount(3877f),
                effects -> effects
                        .withDelayMix(0.46f).withDelayFeedback(0.44f)
                        .withReverbSize(0.7f).withReverbMix(0.22f));
        Instrument pad = Instrument.of(PAD_SYNTH);
        pad.setMidi(1, 74, 64);
        // darker and a little wider than the patch, and ducked deep under each kick, swelling back
        // before the next: the pad breathes with the beat
        tune(pad, DelaySync.FREE, params -> params
                        .withDetune(24.35f).withRelease(0.733f)
                        .withCutoff(1506f).withResonance(0.50f),
                effects -> effects
                        .withReverbSize(0.8f).withReverbMix(0.35f)
                        .withDuck(0.81f, 420));
        Instrument bass = Instrument.of(BASS_SYNTH);
        bass.setMidi(2, 74, 64);
        // five detuned squares through an open, driven filter, a little crushed: a bass that is
        // heard on small speakers too, not only felt, and ducked a little under the kick
        tune(bass, DelaySync.FREE, params -> params
                        .withWaveform(Waveform.SQUARE).withVoices(5).withDetune(18.06f)
                        .withCutoff(4338f).withDrive(0.413f),
                effects -> effects
                        .withDuck(0.32f, 250).withCrush(0.19f));

        TrackList tracks = TrackList.startingWith("Lead", new MelodySource.OwnNotes(lead(), null), lead);
        tracks.add(new StudioTrack.Melody("Pad", 0.45f, false, new MelodySource.OwnNotes(pad(), null), pad));
        tracks.add(new StudioTrack.Melody("Bass", 0.9f, false, new MelodySource.OwnNotes(bass(), null), bass));
        tracks.select(0);
        session.startWith(tracks, Sounds.resolveSynth(LEAD_SYNTH), Sounds.drumPatterns().get(DRUMS), BPM, BARS);
        // what the chord field can name of them: it knows sevenths, not ninths
        session.setChords("Am7 Fmaj7 Dm7 E7");
    }

    private static void tune(Instrument instrument, DelaySync sync, UnaryOperator<SynthParams> sound,
                             UnaryOperator<EffectParams> effects) {
        SynthSetting setting = instrument.setting();
        instrument.set(new SynthSetting(setting.preset(), sound.apply(setting.params()),
                effects.apply(setting.effects()), sync.name()));
    }

    static List<Note> lead() {
        List<Note> notes = new ArrayList<>();
        for (int bar = 0; bar < BARS; bar++) {
            double barStart = bar * Session.BEATS_PER_BAR;
            for (int accent = 0; accent < ACCENTS.length; accent++) {
                int step = ACCENTS[accent];
                // the bar's first note hardest, and its last held over the breath before the next
                float velocity = accent == 0 ? 0.95f : 0.82f;
                double length = accent == ACCENTS.length - 1 ? 2 * SUNG : SUNG;
                int pitch = lower(LINE[bar][accent], CHORDS[bar % CHORDS.length], LOWERED);
                notes.add(new Note(at(barStart, step), new Voice.Pitch(pitch), length, velocity));
            }
            if (bar == BARS - 1) {
                continue;
            }
            // the root an octave under where the line was written, so under it as it is played
            int root = CHORDS[bar % CHORDS.length][0] - 12;
            for (int i = 0; i < PEDAL.length; i++) {
                // every other tick a little harder, so the pedal rolls rather than drones
                notes.add(new Note(at(barStart, PEDAL[i]), new Voice.Pitch(root), TICK, i % 2 == 0 ? 0.5f : 0.4f));
            }
        }
        notes.sort(Comparator.comparingDouble(Note::beat));
        return notes;
    }

    /** {@code pitch} moved {@code steps} notes of {@code chord} down: an inversion lower for every step. */
    static int lower(int pitch, int[] chord, int steps) {
        int lowered = pitch;
        for (int step = 0; step < steps; step++) {
            do {
                lowered--;
            } while (!inChord(lowered, chord));
        }
        return lowered;
    }

    private static boolean inChord(int pitch, int[] chord) {
        for (int note : chord) {
            if (Math.floorMod(note - pitch, 12) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Where sixteenth {@code step} of a bar falls, the off-beat ones a little late. */
    private static double at(double barStart, int step) {
        return barStart + step * 0.25 + (step % 2 == 1 ? SWING : 0);
    }

    static List<Note> pad() {
        List<Note> notes = new ArrayList<>();
        for (int bar = 0; bar < BARS; bar++) {
            for (int pitch : PAD_CHORDS[bar % PAD_CHORDS.length]) {
                // held to just before the next chord, so the two do not smear into one
                notes.add(new Note(bar * Session.BEATS_PER_BAR, new Voice.Pitch(pitch),
                        Session.BEATS_PER_BAR - 0.1, 0.5f));
            }
        }
        return notes;
    }

    static List<Note> bass() {
        List<Note> notes = new ArrayList<>();
        for (int bar = 0; bar < BARS; bar++) {
            for (int beat = 0; beat < Session.BEATS_PER_BAR; beat++) {
                boolean last = bar % ROOTS.length == ROOTS.length - 1 && beat == Session.BEATS_PER_BAR - 1;
                // between the kicks rather than on them, so the two do not fight for the low end,
                // and held up to the next kick
                notes.add(new Note(bar * Session.BEATS_PER_BAR + beat + 0.5,
                        new Voice.Pitch(last ? LEADING_NOTE : ROOTS[bar % ROOTS.length]), 0.48, beat == 0 ? 0.9f : 0.8f));
            }
        }
        return notes;
    }
}
