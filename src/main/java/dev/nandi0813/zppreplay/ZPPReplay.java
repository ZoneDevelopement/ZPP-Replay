package dev.nandi0813.zppreplay;

import dev.nandi0813.api.ZonePracticeApi;
import dev.nandi0813.zppreplay.config.Messages;
import dev.nandi0813.zppreplay.config.ReplaySettings;
import dev.nandi0813.zppreplay.command.ReplayCommand;
import dev.nandi0813.zppreplay.menu.ReplayMenu;
import dev.nandi0813.zppreplay.play.PlaybackManager;
import dev.nandi0813.zppreplay.record.RecordProfiler;
import dev.nandi0813.zppreplay.record.RecordingManager;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class ZPPReplay extends JavaPlugin {

    /** Bump with config.yml VERSION when keys are added; missing keys are copied, existing values kept. */
    private static final int CONFIG_VERSION = 5;
    /** Bump with messages.yml VERSION. */
    private static final int MESSAGES_VERSION = 9;

    @Getter
    private static ZPPReplay instance;

    @Getter
    private Messages messages;
    private ReplayStore store;
    private RecordingManager recordingManager;
    private PlaybackManager playbackManager;

    @Override
    public void onEnable() {
        instance = this;

        if (!ZonePracticeApi.isAvailable() || runtimeApiVersion() < 10) {
            getLogger().severe("ZonePractice Pro with API 2.11.0 or newer (API level 10) is required.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        int storedVersion = getConfig().contains("VERSION", true) ? getConfig().getInt("VERSION") : 0;
        if (storedVersion < CONFIG_VERSION) {
            getConfig().options().copyDefaults(true);
            getConfig().set("VERSION", CONFIG_VERSION);
            saveConfig();
        }
        migrateMessages();
        messages = new Messages(this);

        RecordProfiler profiler = RecordProfiler.create(getLogger(), false);
        store = new ReplayStore(getDataFolder().toPath(), getLogger(), profiler);
        recordingManager = new RecordingManager(this, ReplaySettings.from(getConfig()), store, profiler);
        recordingManager.start();
        playbackManager = new PlaybackManager(this, ReplaySettings.from(getConfig()), store);
        playbackManager.start();
        ReplayCommand replayCommand = new ReplayCommand(this, playbackManager, store);
        Objects.requireNonNull(getCommand("replay")).setExecutor(replayCommand);
        Objects.requireNonNull(getCommand("replay")).setTabCompleter(replayCommand);
        getServer().getPluginManager().registerEvents(new ReplayMenu.Clicks(), this);
    }

    @Override
    public void onDisable() {
        if (playbackManager != null) playbackManager.closeAll();
        playbackManager = null;
        if (recordingManager != null) recordingManager.discardAll();
        if (store != null) store.close();
        store = null;
        recordingManager = null;
        getServer().getScheduler().cancelTasks(this);
        instance = null;
    }

    private void migrateMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
            return;
        }
        YamlConfiguration stored = YamlConfiguration.loadConfiguration(file);
        int from = stored.getInt("VERSION", 0);
        if (from >= MESSAGES_VERSION) return;
        // v6 redesigned the replay menu; reset its old texts so the new layout applies
        if (from < 6) {
            for (String key : List.of("REPLAY.MENU-TITLE", "REPLAY.MENU-ITEM-NAME", "REPLAY.MENU-ITEM-LORE", "REPLAY.MENU-PREVIOUS", "REPLAY.MENU-NEXT")) {
                stored.set(key, null);
            }
        }
        // v5 changed the noclip and usage texts in place; reset them so old installs stop saying "sneak to leave"
        if (from < 7) {
            for (String key : List.of("REPLAY.NOCLIP-ON", "REPLAY.USAGE")) stored.set(key, null);
        }
        // v8 moved the control items into REPLAY.CONTROLS and reworded the noclip hint; reset the old flat texts
        if (from < 8) {
            for (String key : List.of("REPLAY.CONTROL-BACK", "REPLAY.CONTROL-PAUSE", "REPLAY.CONTROL-FORWARD",
                    "REPLAY.CONTROL-SPEED", "REPLAY.CONTROL-FOLLOW", "REPLAY.CONTROL-NOCLIP", "REPLAY.CONTROL-LEAVE",
                    "REPLAY.NOCLIP-ON")) {
                stored.set(key, null);
            }
        }
        try (InputStream resource = getResource("messages.yml")) {
            if (resource != null) {
                stored.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8)));
                stored.options().copyDefaults(true);
            }
            stored.set("VERSION", MESSAGES_VERSION);
            stored.save(file);
        } catch (IOException e) {
            getLogger().warning("Could not migrate messages.yml: " + e.getMessage());
        }
    }

    private static int runtimeApiVersion() {
        try {
            return ZonePracticeApi.class.getField("API_VERSION").getInt(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }
}
