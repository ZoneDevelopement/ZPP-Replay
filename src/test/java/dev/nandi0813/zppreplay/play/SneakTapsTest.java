package dev.nandi0813.zppreplay.play;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SneakTapsTest {

    @Test
    void doubleShortTapExits() {
        SneakTaps taps = new SneakTaps();
        taps.press(100);
        assertFalse(taps.release(102));
        taps.press(105);
        assertTrue(taps.release(107));
    }

    @Test
    void holdThenTapDoesNotExit() {
        SneakTaps taps = new SneakTaps();
        taps.press(100);
        assertFalse(taps.release(110));
        taps.press(112);
        assertFalse(taps.release(114));
    }

    @Test
    void tapHoldTapDoesNotExit() {
        SneakTaps taps = new SneakTaps();
        taps.press(100);
        assertFalse(taps.release(102));
        taps.press(104);
        assertFalse(taps.release(115));
        taps.press(117);
        assertFalse(taps.release(119));
    }

    @Test
    void secondPressAfterEightTicksDoesNotExit() {
        SneakTaps taps = new SneakTaps();
        taps.press(100);
        assertFalse(taps.release(102));
        taps.press(111);
        assertFalse(taps.release(113));
    }
}
