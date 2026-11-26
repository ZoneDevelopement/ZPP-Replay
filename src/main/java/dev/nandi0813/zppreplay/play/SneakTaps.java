package dev.nandi0813.zppreplay.play;

/**
 * Double tap of sneak in noclip: two taps (press and release within {@link #TAP_TICKS}) with the second press within
 * {@link #GAP_TICKS} of the first release. A longer hold never counts and starts the sequence over. Pure Java.
 */
public final class SneakTaps {

    /** 5 ticks, a quarter second, from press to release. */
    private static final int TAP_TICKS = 5;
    /** 8 ticks, 0.4 seconds, from the first tap's release to the second press. */
    private static final int GAP_TICKS = 8;
    private static final int NONE = Integer.MIN_VALUE;

    private int pressedAt = NONE;
    private int tappedAt = NONE;

    public void press(int tick) {
        pressedAt = tick;
    }

    /** True when this release completes a double tap, which then starts over. */
    public boolean release(int tick) {
        int pressed = pressedAt;
        pressedAt = NONE;
        if (pressed == NONE || tick - pressed > TAP_TICKS) {
            tappedAt = NONE;
            return false;
        }
        if (tappedAt != NONE && pressed - tappedAt <= GAP_TICKS) {
            tappedAt = NONE;
            return true;
        }
        tappedAt = tick;
        return false;
    }

    public void reset() {
        pressedAt = NONE;
        tappedAt = NONE;
    }
}
