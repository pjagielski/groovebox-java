package io.groovebox.session;

import io.groovebox.audio.Sample;
import io.groovebox.synth.LiveEffects;
import io.groovebox.audio.Slices;
import io.groovebox.model.Step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One track of the studio's jam as the track list shows it: a name, a gain and a mute, and what
 * it plays. There is one {@link Drums} track, which is the grid, any number of {@link Melody}
 * tracks, each with its own source of notes, and any number of {@link Loop} tracks, each playing a
 * piece of recorded audio in time with the jam.
 */
public sealed interface StudioTrack permits StudioTrack.Drums, StudioTrack.Melody, StudioTrack.Loop {

    String name();

    float gain();

    boolean muted();

    /**
     * Whether this track is the one being listened to. Solo is not the opposite of mute: it says
     * something about the other tracks, so what a soloed track does to them is worked out where the
     * whole list is known - see {@link #audibleGain(boolean)}.
     */
    boolean soloed();

    StudioTrack named(String name);

    StudioTrack withGain(float gain);

    StudioTrack withMuted(boolean muted);

    StudioTrack withSoloed(boolean soloed);

    /** The gain the engine is handed when nothing anywhere is soloed. */
    default float audibleGain() {
        return audibleGain(false);
    }

    /**
     * The gain the engine is handed: the track's own, nothing while it is muted, and nothing while
     * something else is soloed and this is not. Mute still wins over solo, so a track that is both
     * stays silent - a hand that muted something meant it.
     */
    default float audibleGain(boolean anythingSoloed) {
        return muted() || (anythingSoloed && !soloed()) ? 0.0f : gain();
    }

    /** The drum grid, whose notes live in the grid itself rather than here. */
    record Drums(String name, float gain, boolean muted, boolean soloed) implements StudioTrack {

        public Drums {
            Objects.requireNonNull(name, "name");
            checkGain(gain);
        }

        /** Neither muted nor soloed, which is how a track arrives. */
        public Drums(String name, float gain, boolean muted) {
            this(name, gain, muted, false);
        }

        @Override
        public Drums named(String next) {
            return new Drums(next, gain, muted, soloed);
        }

        @Override
        public Drums withGain(float next) {
            return new Drums(name, next, muted, soloed);
        }

        @Override
        public Drums withMuted(boolean next) {
            return new Drums(name, gain, next, soloed);
        }

        @Override
        public Drums withSoloed(boolean next) {
            return new Drums(name, gain, muted, next);
        }
    }

    /** A line of notes of its own or of a MIDI file's, played by an instrument of its own. */
    record Melody(String name, float gain, boolean muted, boolean soloed, MelodySource source,
                  Instrument instrument) implements StudioTrack {

        /** Neither muted nor soloed, which is how a track arrives. */
        public Melody(String name, float gain, boolean muted, MelodySource source, Instrument instrument) {
            this(name, gain, muted, false, source, instrument);
        }

        public Melody {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(source, "source");
            checkGain(gain);
        }

        /** A track with no instrument to play it: what the track list's own tests need, and no more. */
        public Melody(String name, float gain, boolean muted, MelodySource source) {
            this(name, gain, muted, false, source, null);
        }

        /**
         * The MIDI file window this track plays, or the one its own notes were taken from; null for
         * notes with no file behind them.
         */
        public MidiWindow window() {
            return switch (source) {
                case MidiWindow window -> window;
                case MelodySource.OwnNotes own -> own.from();
            };
        }

        @Override
        public Melody named(String next) {
            return new Melody(next, gain, muted, soloed, source, instrument);
        }

        @Override
        public Melody withGain(float next) {
            return new Melody(name, next, muted, soloed, source, instrument);
        }

        @Override
        public Melody withMuted(boolean next) {
            return new Melody(name, gain, next, soloed, source, instrument);
        }

        @Override
        public Melody withSoloed(boolean next) {
            return new Melody(name, gain, muted, next, source, instrument);
        }

        public Melody withSource(MelodySource next) {
            return new Melody(name, gain, muted, soloed, next, instrument);
        }
    }

    /**
     * How a loop is cut up and in what order its slices are played: not at all, or into slices of
     * {@code beats} each, every boundary but the first moved to the hit nearest it when
     * {@code toHits} and there is one to move to.
     *
     * <p>{@code starts} is where the slices begin, worked out once when the cut changes rather than
     * every time the jam is handed over - finding the hits means reading the whole sample.
     * {@code order} is which slice each step of the pass plays, or -1 for a step that strikes
     * nothing; an order that is simply 0, 1, 2 ... is a loop played as it was recorded.
     */
    record Slicing(double beats, boolean toHits, List<Integer> starts, List<Integer> order) {

        public static final Slicing NONE = new Slicing(0, true, List.of(), List.of());

        /** How far a boundary may move to find its hit: a tenth of a slice either way. */
        private static final double NEAR = 0.1;

        public Slicing {
            starts = List.copyOf(starts);
            order = List.copyOf(order);
        }

        public boolean on() {
            return beats > 0 && !starts.isEmpty();
        }

        public int count() {
            return starts.size();
        }

        /**
         * Where the slices of {@code audio} fall for a cut of {@code beats} over a pass of
         * {@code lengthBeats}, with {@code wanted} kept where it still fits and the rest of the
         * order laid out plainly. This is the one place a cut is worked out.
         */
        public static Slicing of(Sample audio, double beats, boolean toHits, double lengthBeats, List<Integer> wanted) {
            if (beats <= 0 || lengthBeats <= 0) {
                return NONE;
            }
            int count = Math.max(1, (int) Math.round(lengthBeats / beats));
            int[] found = toHits ? Slices.onTheHits(audio, count, NEAR)
                    : Slices.onTheGrid(audio.frameCount(), count);
            List<Integer> starts = new ArrayList<>(found.length);
            for (int start : found) {
                starts.add(start);
            }
            List<Integer> order = new ArrayList<>(count);
            for (int step = 0; step < count; step++) {
                int was = step < wanted.size() ? wanted.get(step) : step;
                // a step naming a slice the cut no longer has goes back to naming its own
                order.add(was >= count ? step : was);
            }
            return new Slicing(beats, toHits, starts, order);
        }

        /** The same cut with {@code step} playing {@code slice}, or -1 for nothing struck there. */
        public Slicing with(int step, int slice) {
            List<Integer> next = new ArrayList<>(order);
            next.set(step, slice);
            return new Slicing(beats, toHits, starts, next);
        }

        /** The same cut, played as it was recorded. */
        public Slicing straightened() {
            List<Integer> next = new ArrayList<>(count());
            for (int step = 0; step < count(); step++) {
                next.add(step);
            }
            return new Slicing(beats, toHits, starts, next);
        }

        /** Whether the slices are played in the order they were recorded in, which is the plain case. */
        public boolean straight() {
            for (int step = 0; step < order.size(); step++) {
                if (order.get(step) != step) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The steps the engine plays, or nothing at all where the order is the plain one - a loop
         * played as recorded is one piece of audio struck once, not a slice struck at every step.
         */
        public List<Step> steps(int frameCount) {
            if (!on() || straight()) {
                return List.of();
            }
            List<Step> steps = new ArrayList<>(order.size());
            for (int slice : order) {
                if (slice < 0 || slice >= starts.size()) {
                    steps.add(Step.REST);
                    continue;
                }
                int from = starts.get(slice);
                int until = slice + 1 < starts.size() ? starts.get(slice + 1) : frameCount;
                steps.add(new Step(from, until));
            }
            return steps;
        }
    }

    /** A piece of recorded audio, played over as many bars of the jam as it is taken to fill. */
    record Loop(String name, float gain, boolean muted, boolean soloed, Path file, double bars,
                Sample audio, Slicing slicing, LiveEffects channel, String delaySync)
            implements StudioTrack {

        /** Neither muted nor soloed, which is how a track arrives. */
        public Loop(String name, float gain, boolean muted, Path file, double bars, Sample audio, Slicing slicing) {
            this(name, gain, muted, false, file, bars, audio, slicing);
        }

        /**
         * A channel of its own, dry to begin with. Made here rather than passed in, so that every
         * loop has one and the {@code with} methods carry the same one through: it is the thing the
         * engine keeps a bus for, and a new one each time the panel moved a fader would be a new bus
         * each time, and a delay that never gets to repeat.
         */
        public Loop(String name, float gain, boolean muted, boolean soloed, Path file, double bars,
             Sample audio, Slicing slicing) {
            this(name, gain, muted, soloed, file, bars, audio, slicing, new LiveEffects(), FREE_DELAY);
        }

        public Loop {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(audio, "audio");
            Objects.requireNonNull(slicing, "slicing");
            Objects.requireNonNull(channel, "channel");
            delaySync = delaySync == null ? FREE_DELAY : delaySync;
            checkGain(gain);
            if (!Double.isFinite(bars) || bars <= 0) {
                throw new IllegalArgumentException("A loop fills a positive number of bars, not " + bars);
            }
        }

        /** A loop with nothing cut out of it yet, which is how one is first chosen. */
        public Loop(String name, float gain, boolean muted, Path file, double bars, Sample audio) {
            this(name, gain, muted, false, file, bars, audio, Slicing.NONE);
        }

        @Override
        public Loop named(String next) {
            return new Loop(next, gain, muted, soloed, file, bars, audio, slicing, channel, delaySync);
        }

        @Override
        public Loop withGain(float next) {
            return new Loop(name, next, muted, soloed, file, bars, audio, slicing, channel, delaySync);
        }

        @Override
        public Loop withMuted(boolean next) {
            return new Loop(name, gain, next, soloed, file, bars, audio, slicing, channel, delaySync);
        }

        public Loop over(double nextBars) {
            return new Loop(name, gain, muted, soloed, file, nextBars, audio, slicing, channel, delaySync);
        }

        @Override
        public Loop withSoloed(boolean next) {
            return new Loop(name, gain, muted, next, file, bars, audio, slicing, channel, delaySync);
        }

        public Loop cutInto(Slicing next) {
            return new Loop(name, gain, muted, soloed, file, bars, audio, next, channel, delaySync);
        }

        /**
         * The same loop with its delay locked to another division. The effect settings themselves
         * are not here: they live in the channel, which the engine reads as they are turned, so a
         * knob is heard without the jam being published again. Only the division has to be kept,
         * because it is a thing the panel says rather than a thing the audio reads.
         */
        public Loop lockedTo(String division) {
            return new Loop(name, gain, muted, soloed, file, bars, audio, slicing, channel, division);
        }

        /** The tempo the audio was cut at, as far as its length and its bars tell. */
        public double sourceBpm(int sampleRate, int beatsPerBar) {
            return bars * beatsPerBar * 60.0 * sampleRate / audio.frameCount();
        }
    }

    /** A delay running free rather than locked to a division of the beat: how a loop arrives. */
    String FREE_DELAY = "FREE";

    private static void checkGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }
}
