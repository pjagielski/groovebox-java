package io.groovebox.audio;

/**
 * Where a loop's slices begin, in frames. A loop is cut on its own grid first - so many to a pass,
 * evenly - because that is what its bars say it is. The grid is then let go of a little: a break
 * cut by hand, or played by a drummer, has its hits near the beat rather than on it, and a slice
 * that begins a few milliseconds before its hit begins with the tail of the one before.
 *
 * <p>The hits are found the plainest way there is: the loudest sample of each short hop, and where
 * that jumps from one hop to the next there is the start of something. It is enough to tell a kick
 * from the silence in front of it, which is all that is being asked. Nothing moves unless there is
 * something to move to, and the first slice never moves: it is the top of the pass, and the jam is
 * counting on it.
 *
 * <p>A boundary takes the nearest hit worth having, not the loudest one within reach. The loudest
 * is the wrong rule: a snare two hundred milliseconds away is louder than the hi-hat a boundary is
 * actually sitting on, and a boundary that jumps that far has left its own slice behind. So the
 * strongest rise in the window sets the bar, and the nearest rise that clears half of it wins.
 */
public final class Slices {

    /** About six milliseconds at 44.1 kHz: short enough to place a hit, long enough to be cheap. */
    private static final int HOP = 256;

    /** Below this, a rise is the noise of a decaying tail rather than the start of anything. */
    private static final float RISE = 0.02f;

    private Slices() {
    }

    /** {@code count} slices of equal length, the first at frame 0. */
    public static int[] onTheGrid(int frameCount, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("A loop is cut into at least one slice, not " + count);
        }
        int[] starts = new int[count];
        for (int slice = 0; slice < count; slice++) {
            starts[slice] = (int) ((long) slice * frameCount / count);
        }
        return starts;
    }

    /**
     * The same grid, with every boundary but the first moved to the strongest rise within
     * {@code within} of a slice's length of it - when there is one worth moving to.
     */
    public static int[] onTheHits(Sample audio, int count, double within) {
        int[] starts = onTheGrid(audio.frameCount(), count);
        float[] rises = rises(audio);
        int slice = audio.frameCount() / count;
        int search = (int) Math.max(HOP, slice * within);
        for (int at = 1; at < starts.length; at++) {
            int moved = nearestRise(rises, starts[at], search);
            if (moved >= 0) {
                starts[at] = moved;
            }
        }
        return starts;
    }

    /** How much louder each hop is than the one before it; the first is nothing to compare. */
    private static float[] rises(Sample audio) {
        float[] mono = audio.copyMono();
        int hops = Math.max(1, mono.length / HOP);
        float[] loudest = new float[hops];
        for (int hop = 0; hop < hops; hop++) {
            float peak = 0;
            for (int frame = hop * HOP; frame < (hop + 1) * HOP && frame < mono.length; frame++) {
                peak = Math.max(peak, Math.abs(mono[frame]));
            }
            loudest[hop] = peak;
        }
        float[] rises = new float[hops];
        for (int hop = 1; hop < hops; hop++) {
            rises[hop] = loudest[hop] - loudest[hop - 1];
        }
        return rises;
    }

    /** The frame the nearest hit near {@code around} starts at, or -1 when there is none. */
    private static int nearestRise(float[] rises, int around, int search) {
        int from = Math.max(1, (around - search) / HOP);
        int to = Math.min(rises.length - 1, (around + search) / HOP);
        float strongest = 0;
        for (int hop = from; hop <= to; hop++) {
            strongest = Math.max(strongest, rises[hop]);
        }
        if (strongest < RISE) {
            return -1;
        }
        // half the strongest in the window: enough to be the same kind of thing, near enough to be ours
        float worth = Math.max(RISE, strongest / 2);
        int best = -1;
        int closest = Integer.MAX_VALUE;
        for (int hop = from; hop <= to; hop++) {
            int away = Math.abs(hop * HOP - around);
            if (rises[hop] >= worth && away < closest) {
                closest = away;
                best = hop;
            }
        }
        return best < 0 ? -1 : best * HOP;
    }
}
