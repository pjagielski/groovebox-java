package io.groovebox.model;

/**
 * How a percussive hit is shaped: {@code decaySeconds} is how long it takes to die away from its
 * peak, {@code releaseSeconds} how long it fades out once its note has ended, and
 * {@code semitones} how far it is tuned from the sample as recorded, by playing the sample faster
 * or slower. Zero leaves the sound as it is, for each.
 */
public record Envelope(double decaySeconds, double releaseSeconds, double semitones) {

    public static final Envelope NONE = new Envelope(0.0, 0.0);

    public Envelope {
        if (!(decaySeconds >= 0.0) || !(releaseSeconds >= 0.0)) {
            throw new IllegalArgumentException("Envelope times must be zero or positive");
        }
        if (!Double.isFinite(semitones)) {
            throw new IllegalArgumentException("A tuning is a number of semitones");
        }
    }

    /** A hit decayed and released as recorded in pitch. */
    public Envelope(double decaySeconds, double releaseSeconds) {
        this(decaySeconds, releaseSeconds, 0.0);
    }

    public boolean shapes() {
        return decaySeconds > 0.0 || releaseSeconds > 0.0 || semitones != 0.0;
    }

    public Envelope withDecay(double seconds) {
        return new Envelope(seconds, releaseSeconds, semitones);
    }

    public Envelope tunedTo(double nextSemitones) {
        return new Envelope(decaySeconds, releaseSeconds, nextSemitones);
    }
}
