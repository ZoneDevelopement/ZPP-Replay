package dev.nandi0813.zppreplay.play;

import dev.nandi0813.zppreplay.format.ReplayReader.Rec;
import java.util.List;

/** Playback position of one viewer in replay ticks. Moves by the speed on every server tick. Pure Java. */
public final class ReplayClock {

    private static final double[] SPEEDS = {0.5, 1, 2, 4};
    private static final int DEFAULT_SPEED = 1;

    private final int duration;
    private double position;
    private int speedIndex = DEFAULT_SPEED;
    private boolean paused;

    public ReplayClock(int duration) {
        this.duration = Math.max(0, duration);
    }

    /** One server tick. Paused and finished clocks stay where they are. */
    public void advance() {
        if (!paused && position < duration) position = Math.min(duration, position + SPEEDS[speedIndex]);
    }

    /** The replay tick the clock stands at, rounded down. */
    public int tick() {
        return (int) position;
    }

    public int duration() {
        return duration;
    }

    public boolean finished() {
        return position >= duration;
    }

    /** Clamped to [0, duration]. */
    public void seek(int target) {
        position = Math.max(0, Math.min(duration, target));
    }

    public double speed() {
        return SPEEDS[speedIndex];
    }

    public void setSpeed(double speed) {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == speed) {
                speedIndex = i;
                return;
            }
        }
        throw new IllegalArgumentException("Unknown playback speed " + speed);
    }

    public void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
    }

    public String speedLabel() {
        double speed = speed();
        return speed == Math.rint(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void togglePause() {
        paused = !paused;
    }

    /** {@code m:ss} for a tick count, 20 ticks per second. */
    public static String format(int ticks) {
        int seconds = ticks / 20;
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /**
     * The index of the first record that is not applied when playback moves from the tick {@code tick}, reached by
     * records from {@code from}, up to {@code target}. Records stop before the first TICK that would pass the target.
     */
    public static int cursorAt(List<Rec> records, int from, int tick, int target) {
        int current = tick;
        for (int i = from; i < records.size(); i++) {
            if (records.get(i) instanceof Rec.Tick next) {
                if (current + next.delta() > target) return i;
                current += next.delta();
            }
        }
        return records.size();
    }
}
