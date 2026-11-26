package dev.nandi0813.zppreplay.config;

import org.bukkit.configuration.file.FileConfiguration;

/** Recording and storage limits from config.yml. Out-of-range values are clamped. */
public record ReplaySettings(boolean recordingEnabled, int maxConcurrent, int maxPlayersPerMatch, int maxDurationMinutes,
                             boolean showDeadSpectators, int defaultPerPlayer, int maxConcurrentViewers) {

    public static ReplaySettings from(FileConfiguration config) {
        return new ReplaySettings(
                config.getBoolean("RECORDING.ENABLED", true),
                clamp(config.getInt("RECORDING.MAX-CONCURRENT", 20), 1, 256),
                clamp(config.getInt("RECORDING.MAX-PLAYERS-PER-MATCH", 16), 2, 64),
                clamp(config.getInt("RECORDING.MAX-DURATION-MINUTES", 30), 1, 120),
                config.getBoolean("RECORDING.SHOW-DEAD-SPECTATORS", false),
                clamp(config.getInt("STORAGE.DEFAULT-PER-PLAYER", 10), 1, 1000),
                clamp(config.getInt("PLAYBACK.MAX-CONCURRENT-VIEWERS", 10), 1, 100));
    }

    public int maxDurationTicks() {
        return maxDurationMinutes * 60 * 20;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
