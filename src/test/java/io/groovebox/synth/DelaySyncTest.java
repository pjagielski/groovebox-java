package io.groovebox.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DelaySyncTest {

    @Test
    void aDivisionIsAsLongAsTheTempoSaysItIs() {
        assertEquals(2_000, DelaySync.QUARTER.millis(30), 1e-9);
        assertEquals(500, DelaySync.QUARTER.millis(120), 1e-9);
        assertEquals(250, DelaySync.EIGHTH.millis(120), 1e-9);
        assertEquals(375, DelaySync.DOTTED_EIGHTH.millis(120), 1e-9);
        assertEquals(125, DelaySync.SIXTEENTH.millis(120), 1e-9);
    }

    @Test
    void aTripletIsThreeToTheBeat() {
        // three eighth-note triplets fill one beat, so each is a third of it
        assertEquals(DelaySync.QUARTER.millis(120) / 3, DelaySync.TRIPLET_EIGHTH.millis(120), 1e-9);
    }

    @Test
    void aLockedDelayChangesItsTimeAndNothingElse() {
        EffectParams set = EffectParams.DEFAULT.withDelayMix(0.4f)
                .driven(Saturation.values()[1], 1.2f, 0.8f)
                .filtered(ChannelFilter.LADDER, 900, 0.5f);

        EffectParams timed = DelaySync.EIGHTH.timed(set, 120);

        assertEquals(250, timed.delayMillis(), 1e-3);
        assertEquals(set.withDelayMillis(250), timed);
    }

    @Test
    void aFreeDelayKeepsItsOwnTime() {
        EffectParams set = EffectParams.DEFAULT;

        assertSame(set, DelaySync.FREE.timed(set, 120));
        assertSame(DelaySync.FREE, DelaySync.named("no such division"));
    }
}
