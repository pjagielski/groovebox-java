package io.groovebox.controls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamStepsTest {

    @Test
    void aSteppedParamReadsOutNamesRatherThanNumbers() {
        Param wave = Param.steps("Wave", "Saw", "Square", "Sine");

        assertTrue(wave.stepped());
        assertEquals("Saw", wave.format(0));
        assertEquals("Square", wave.format(1));
        assertEquals("Sine", wave.format(2));
    }

    @Test
    void itLandsOnTheNearestOfThem() {
        Param wave = Param.steps("Wave", "Saw", "Square", "Sine");

        assertEquals(0, wave.step(0.4));
        assertEquals(1, wave.step(0.6));
        assertEquals(1, wave.step(1.49));
        assertEquals(2, wave.step(1.5));
        // and never off either end, whatever it is handed
        assertEquals(0, wave.step(-9));
        assertEquals(2, wave.step(99));
    }

    @Test
    void itSpansTheTravelFromTheFirstNameToTheLast() {
        Param shape = Param.steps("Shape", "a", "b", "c", "d", "e", "f", "g", "h", "i");

        assertEquals(0.0, shape.min());
        assertEquals(8.0, shape.max());
        assertEquals(0.0, shape.positionOf(0), 1e-9);
        assertEquals(1.0, shape.positionOf(8), 1e-9);
    }

    @Test
    void anOrdinaryParamIsNotStepped() {
        assertFalse(Param.linear("Drive", 0, 2, "", 2, 1).stepped());
        assertEquals("1.00", Param.linear("Drive", 0, 2, "", 2, 1).format(1));
    }
}
