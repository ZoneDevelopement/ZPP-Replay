package dev.nandi0813.zppreplay.format;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nandi0813.zppreplay.format.ReplayReader.Replay;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Synthetic worst case: 16 actors for 30 minutes of ticks, every record type at its densest. */
class WriterLoadTest {

    private static final int ACTORS = 16;
    private static final int TICKS = 36_000;
    private static final ReplayWriter.Header HEADER = new ReplayWriter.Header("loadtest", "match-load", "NoDebuff",
            "DUEL", "Original", "Arena copy", 1_700_000_000_000L, TICKS, false, 40.0, 20.0, 40.0);

    private record Result(double nsPerActorTick, int rawBytes, int fileBytes, long readMillis) {
    }

    @Test
    void thirtyMinutesOfSixteenActorsStaysWithinBudget() throws IOException {
        run(); // warm-up: JIT and class loading, the same work as the measured run
        Result result = run();

        System.out.println("REPLAY_LOAD nsPerActorTick=" + Math.round(result.nsPerActorTick()) + " rawBytes="
                + result.rawBytes() + " fileBytes=" + result.fileBytes() + " readMillis=" + result.readMillis());

        assertTrue(result.nsPerActorTick() < 5000, "nsPerActorTick " + result.nsPerActorTick());
        assertTrue(result.fileBytes() < 16 * 1024 * 1024, "fileBytes " + result.fileBytes());
        assertTrue(result.readMillis() < 5000, "readMillis " + result.readMillis());
    }

    private static Result run() throws IOException {
        Random random = new Random(42);
        ReplayWriter writer = new ReplayWriter();
        long writeNanos = 0;
        byte[] item = new byte[200];

        for (int tick = 0; tick < TICKS; tick++) {
            random.nextBytes(item); // generated outside the timed section
            long start = System.nanoTime();
            writer.writeTick(1);
            for (int actor = 0; actor < ACTORS; actor++) {
                float x = actor * 3f + tick * 0.05f;
                float z = tick * 0.03f - actor;
                writer.writePos(actor, x, 64f, z, (byte) (tick + actor), (byte) (tick * 3), (byte) (tick - actor));
                if (tick % 20 == 0) writer.writeState(actor, (byte) (tick & 0x7F), (byte) (tick % 6));
                if (tick % 100 == 0) writer.writeEquip(actor, (byte) (actor % 6), item);
            }
            if (tick % 10 == 0) writer.writeBlock((short) (tick % 200), (short) 64, (short) (tick % 50), tick % 16);
            writeNanos += System.nanoTime() - start;
        }
        writer.writeEnd();

        byte[] file = ReplayWriter.file(HEADER, writer.body());
        long readStart = System.nanoTime();
        Replay replay = ReplayReader.read(file);
        long readMillis = (System.nanoTime() - readStart) / 1_000_000L;
        assertTrue(replay.records().size() > TICKS, "records " + replay.records().size());

        double nsPerActorTick = (double) writeNanos / ((double) ACTORS * TICKS);
        return new Result(nsPerActorTick, writer.size(), file.length, readMillis);
    }
}
