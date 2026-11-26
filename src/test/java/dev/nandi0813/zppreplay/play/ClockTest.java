package dev.nandi0813.zppreplay.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClockTest {

    @Test
    void advancesBySpeedOverTwentyServerTicks() {
        for (double speed : new double[] {0.5, 1, 2, 4}) {
            ReplayClock clock = new ReplayClock(1000);
            clock.setSpeed(speed);
            for (int i = 0; i < 20; i++) clock.advance();
            assertEquals((int) (20 * speed), clock.tick(), "speed " + speed);
        }
    }

    @Test
    void halfSpeedMovesOneTickEverySecondServerTick() {
        ReplayClock clock = new ReplayClock(1000);
        clock.setSpeed(0.5);
        clock.advance();
        assertEquals(0, clock.tick());
        clock.advance();
        assertEquals(1, clock.tick());
    }

    @Test
    void seekIsClampedToTheReplay() {
        ReplayClock clock = new ReplayClock(1000);
        clock.seek(-5);
        assertEquals(0, clock.tick());
        clock.seek(5000);
        assertEquals(1000, clock.tick());
        clock.seek(300);
        assertEquals(300, clock.tick());
    }

    @Test
    void finishesAtTheDurationAndStops() {
        ReplayClock clock = new ReplayClock(10);
        clock.setSpeed(4);
        for (int i = 0; i < 3; i++) clock.advance();
        assertTrue(clock.finished());
        assertEquals(10, clock.tick());
    }

    @Test
    void pausedClockDoesNotMove() {
        ReplayClock clock = new ReplayClock(1000);
        clock.togglePause();
        assertTrue(clock.paused());
        clock.advance();
        assertEquals(0, clock.tick());
        clock.togglePause();
        assertFalse(clock.paused());
    }

    @Test
    void speedCyclesThroughTheSteps() {
        ReplayClock clock = new ReplayClock(10);
        assertEquals(1, clock.speed());
        clock.cycleSpeed();
        assertEquals(2, clock.speed());
        clock.cycleSpeed();
        assertEquals(4, clock.speed());
        clock.cycleSpeed();
        assertEquals(0.5, clock.speed());
        assertEquals("0.5", clock.speedLabel());
        assertEquals("1", new ReplayClock(10).speedLabel());
    }

    @Test
    void formatsMinutesAndSeconds() {
        assertEquals("0:00", ReplayClock.format(0));
        assertEquals("1:01", ReplayClock.format(1230));
    }
}
