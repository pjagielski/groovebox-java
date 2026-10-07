package io.groovebox.session;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many bars a piece of recorded audio is taken to fill when it is first chosen. Nothing in a
 * WAV file says how long a bar is, so this is a guess, and the studio shows it as one: the number
 * is put in a box the hand can change.
 *
 * <p>The guess is made first from the file's name, since a folder of breaks is usually named for
 * the tempo and the length they were cut at, and otherwise from how long the file is at the tempo
 * the jam is at now, rounded to a length a loop is actually ever cut to.
 */
public final class LoopBars {

    /** The way a folder of breaks names them: the tempo, how many beats long, then the name. */
    private static final Pattern NAMED = Pattern.compile("^(\\d{2,3})[_-](\\d{1,3})[_-].+");

    /** The lengths a loop is nearly always cut to, which a guess from the jam's tempo lands on. */
    private static final double[] USUAL = {0.25, 0.5, 1, 2, 4, 8, 16, 32};

    private LoopBars() {
    }

    /** The bars {@code file} is taken to fill, given how long it is and what tempo the jam is at. */
    public static double guess(Path file, long frameCount, int sampleRate, int beatsPerBar, double jamBpm) {
        Matcher named = NAMED.matcher(file.getFileName().toString());
        if (named.matches()) {
            double bars = Double.parseDouble(named.group(2)) / beatsPerBar;
            if (bars > 0) {
                return bars;
            }
        }
        double seconds = frameCount / (double) sampleRate;
        return nearest(seconds * jamBpm / 60.0 / beatsPerBar);
    }

    /**
     * The usual length closest to {@code bars} — closest in how far it would have to be sped up or
     * slowed down, not in bars, since two bars taken for three is as wrong as three taken for two.
     */
    public static double nearest(double bars) {
        if (!(bars > 0) || !Double.isFinite(bars)) {
            return 1;
        }
        double best = USUAL[0];
        double bestOff = Double.MAX_VALUE;
        for (double usual : USUAL) {
            double off = Math.abs(Math.log(usual / bars));
            if (off < bestOff) {
                bestOff = off;
                best = usual;
            }
        }
        return best;
    }

    /** A length of bars as the studio writes it: whole where it can be, a fraction where it cannot. */
    public static String label(double bars) {
        if (bars == Math.rint(bars)) {
            return String.valueOf((long) bars);
        }
        return String.valueOf(bars).replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }
}
