package io.groovebox.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportTest {

    @Test
    void derivesFractionalStepPositionsFromTheAbsoluteBeatWithoutDrift() {
        var transport = new Transport(120, 44_100);

        long[] frames = {
                transport.frameAtBeat(0.00),
                transport.frameAtBeat(0.25),
                transport.frameAtBeat(0.50),
                transport.frameAtBeat(0.75),
                transport.frameAtBeat(1.00)
        };

        assertArrayEquals(new long[]{0, 5_513, 11_025, 16_538, 22_050}, frames);
        assertEquals(88_200, transport.frameAtBeat(4.0));
    }
}
