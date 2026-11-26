package dev.nandi0813.zppreplay.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nandi0813.zppreplay.play.Controls;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Bundled resources must parse; a broken messages.yml otherwise shows raw keys in game. */
class ResourceYamlTest {

    /** Every REPLAY.* message path the code sends or gets; keep in sync with the sources. */
    private static final String[] REPLAY_KEYS = {
            "REPLAY.ACTION-BAR", "REPLAY.ACTION-BAR-NOCLIP", "REPLAY.ALREADY-WATCHING", "REPLAY.BUSY",
            "REPLAY.COMMAND-BLOCKED", "REPLAY.CONTROL-NO-TARGET", "REPLAY.CONTROL-PAUSED", "REPLAY.CONTROL-PLAYING",
            "REPLAY.CONTROLS.BACK.NAME", "REPLAY.CONTROLS.BACK.LORE", "REPLAY.CONTROLS.PAUSE.NAME",
            "REPLAY.CONTROLS.PAUSE.LORE", "REPLAY.CONTROLS.FORWARD.NAME", "REPLAY.CONTROLS.FORWARD.LORE",
            "REPLAY.CONTROLS.SPEED.NAME", "REPLAY.CONTROLS.SPEED.LORE", "REPLAY.CONTROLS.FOLLOW.NAME",
            "REPLAY.CONTROLS.FOLLOW.LORE", "REPLAY.CONTROLS.NOCLIP.NAME", "REPLAY.CONTROLS.NOCLIP.LORE",
            "REPLAY.CONTROLS.LEAVE.NAME", "REPLAY.CONTROLS.LEAVE.LORE", "REPLAY.ENDED", "REPLAY.FULL",
            "REPLAY.LEFT", "REPLAY.LOAD-FAILED", "REPLAY.LOADING", "REPLAY.MENU-ITEM-LORE",
            "REPLAY.NOCLIP-OFF", "REPLAY.NOCLIP-ON", "REPLAY.NOCLIP-SUBTITLE", "REPLAY.NOCLIP-TITLE",
            "REPLAY.MENU-ITEM-FOOTER", "REPLAY.MENU-ITEM-MORE", "REPLAY.MENU-ITEM-PLAYER", "REPLAY.MENU-ITEM-NAME",
            "REPLAY.MENU-NEXT", "REPLAY.MENU-PAGE", "REPLAY.MENU-PREVIOUS", "REPLAY.MENU-TITLE",
            "REPLAY.NO-ARENA", "REPLAY.NO-PERMISSION", "REPLAY.NO-REPLAYS", "REPLAY.NOT-FOUND", "REPLAY.NOT-WATCHING",
            "REPLAY.ROUND",
            "REPLAY.STARTED", "REPLAY.USAGE",
    };

    @Test
    void bundledYamlFilesParse() {
        for (String name : List.of("plugin.yml", "config.yml", "messages.yml")) {
            assertDoesNotThrow(() -> new YamlConfiguration().loadFromString(read(name)), name);
        }
    }

    @Test
    void messagesHaveThePrefixAndEveryReplayKey() throws Exception {
        YamlConfiguration messages = new YamlConfiguration();
        messages.loadFromString(read("messages.yml"));

        assertTrue(messages.contains("PREFIX"), "PREFIX");
        for (String key : REPLAY_KEYS) {
            assertTrue(messages.contains(key), key);
        }
    }

    @Test
    void configHasASlotForEveryControl() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(read("config.yml"));

        for (Controls.Action action : Controls.Action.values()) {
            assertTrue(config.contains("CONTROLS." + action.name() + ".SLOT"), action.name());
        }
    }

    @Test
    void deadSpectatorsStayHiddenByDefaultAndTheSwitchIsRead() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(read("config.yml"));

        assertTrue(config.contains("RECORDING.SHOW-DEAD-SPECTATORS"), "RECORDING.SHOW-DEAD-SPECTATORS");
        assertFalse(ReplaySettings.from(config).showDeadSpectators());
        config.set("RECORDING.SHOW-DEAD-SPECTATORS", true);
        assertTrue(ReplaySettings.from(config).showDeadSpectators());
    }

    private static String read(String name) throws IOException {
        try (InputStream in = ResourceYamlTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
