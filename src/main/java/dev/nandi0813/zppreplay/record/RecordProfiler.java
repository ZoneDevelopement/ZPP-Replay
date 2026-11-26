package dev.nandi0813.zppreplay.record;

import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Timing of the recorder: tick and block-event cost (REPLAY_PROFILE), per-phase cost of a tick
 * (REPLAY_PHASES) and save time (REPLAY_SAVED). On only with {@code -Dzpp.replay.profile=true}.
 * Main thread only, except {@link #saved}, which logs.
 */
public final class RecordProfiler {

    /** Ticks per report. */
    static final int WINDOW = 200;
    /** Phases of {@link Recording#tick}, indexes into the phase accumulator. */
    static final int DISCOVER = 0;
    static final int ACTORS = 1;
    static final int EQUIP = 2;
    static final int ENTITIES = 3;
    static final int BLOCKS = 4;
    private static final int PHASES = 5;
    private static final String PROPERTY = "zpp.replay.profile";

    private final Logger log;
    private final boolean enabled;
    private final long[] ring = new long[WINDOW];
    private int samples;
    private long sum;
    private long max;
    private long actorSum;
    private long blockEvents;
    private long blockSum;
    private final long[] phases = new long[PHASES];

    private RecordProfiler(Logger log, boolean enabled) {
        this.log = log;
        this.enabled = enabled;
    }

    public static RecordProfiler create(Logger log, boolean release) {
        boolean requested = Boolean.getBoolean(PROPERTY);
        if (requested && release) {
            log.warning("-D" + PROPERTY + " is ignored on release builds");
            return new RecordProfiler(log, false);
        }
        return new RecordProfiler(log, requested);
    }

    public boolean enabled() {
        return enabled;
    }

    /** One tick with recordings: its duration and the number of actors recorded in it. */
    public void tick(long nanos, int actors) {
        ring[samples++] = nanos;
        sum += nanos;
        max = Math.max(max, nanos);
        actorSum += actors;
    }

    /** True when a window is full and {@link #report} should run. */
    public boolean full() {
        return samples == WINDOW;
    }

    /** One phase of one recorded tick; summed over the window and reported as an average per server tick. */
    public void phase(int index, long nanos) {
        phases[index] += nanos;
    }

    public void blockEvent(long nanos) {
        blockEvents++;
        blockSum += nanos;
    }

    /** Logs one REPLAY_PROFILE line for the window and resets it. Logs nothing when no tick was sampled. */
    public void report(int recordings, long bufferBytes) {
        if (samples == 0) return;
        long[] sorted = Arrays.copyOf(ring, samples);
        Arrays.sort(sorted);
        log.info(String.format(Locale.ROOT,
                "REPLAY_PROFILE recordings=%d actors=%.1f tickAvgUs=%d tickP99Us=%d tickMaxUs=%d nsPerActorTick=%d"
                        + " blockEvents=%d blockAvgNs=%d bufferKB=%d mspt=%.2f",
                recordings, (double) actorSum / samples, sum / samples / 1000, percentile(sorted, 99) / 1000, max / 1000,
                actorSum == 0 ? 0 : sum / actorSum, blockEvents, blockEvents == 0 ? 0 : blockSum / blockEvents,
                bufferBytes / 1024, Bukkit.getServer().getAverageTickTime()));
        log.info(String.format(Locale.ROOT, "REPLAY_PHASES discoverNs=%d actorsNs=%d equipNs=%d entitiesNs=%d blocksNs=%d",
                phases[DISCOVER] / samples, phases[ACTORS] / samples, phases[EQUIP] / samples,
                phases[ENTITIES] / samples, phases[BLOCKS] / samples));
        Arrays.fill(phases, 0L);
        samples = 0;
        sum = 0;
        max = 0;
        actorSum = 0;
        blockEvents = 0;
        blockSum = 0;
    }

    /** Called on the writer thread. */
    public void saved(String id, int bytes, long writeNanos) {
        log.info(String.format(Locale.ROOT, "REPLAY_SAVED id=%s bytes=%d writeMs=%.2f", id, bytes, writeNanos / 1e6));
    }

    /** Nearest-rank percentile of a sorted, non-empty array; {@code percent} is 1..100. */
    static long percentile(long[] sorted, int percent) {
        int rank = (sorted.length * percent + 99) / 100;
        return sorted[Math.max(0, rank - 1)];
    }
}
