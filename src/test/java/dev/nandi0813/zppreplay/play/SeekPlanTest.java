package dev.nandi0813.zppreplay.play;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nandi0813.zppreplay.format.ReplayReader.Rec;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeekPlanTest {

    /** Ticks: 0 -> 5 (Pos), 5 -> 8 (Round), 8 -> 10 (Hurt). */
    private static final List<Rec> RECORDS = List.of(
            new Rec.Tick(5),
            new Rec.Pos(0, 1f, 2f, 3f, (byte) 0, (byte) 0, (byte) 0),
            new Rec.Tick(3),
            new Rec.Round(2),
            new Rec.Tick(2),
            new Rec.Hurt(0));

    @Test
    void stopsBeforeTheFirstTickPastTheTarget() {
        assertEquals(0, ReplayClock.cursorAt(RECORDS, 0, 0, 0));
        assertEquals(2, ReplayClock.cursorAt(RECORDS, 0, 0, 5));
        assertEquals(4, ReplayClock.cursorAt(RECORDS, 0, 0, 8));
        assertEquals(RECORDS.size(), ReplayClock.cursorAt(RECORDS, 0, 0, 100));
    }

    @Test
    void resumesFromAnEarlierCursorAndTick() {
        assertEquals(4, ReplayClock.cursorAt(RECORDS, 2, 5, 8));
    }
}
