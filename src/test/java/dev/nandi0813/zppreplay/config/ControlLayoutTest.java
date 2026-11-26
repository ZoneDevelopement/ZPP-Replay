package dev.nandi0813.zppreplay.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nandi0813.zppreplay.play.Controls.Action;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ControlLayoutTest {

    /** Material.isItem needs a running server, so the tests treat every material as an item. */
    private static final Predicate<Material> ANY_ITEM = material -> true;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void defaultsParseToTheBundledLayout() throws Exception {
        ControlLayout layout = parse(bundled());

        assertEquals(7, layout.entries().size());
        assertEquals(new ControlLayout.Entry(0, Material.ENDER_EYE), layout.entries().get(Action.FOLLOW));
        assertEquals(new ControlLayout.Entry(1, Material.FEATHER), layout.entries().get(Action.NOCLIP));
        assertEquals(new ControlLayout.Entry(3, Material.ARROW), layout.entries().get(Action.BACK));
        assertEquals(new ControlLayout.Entry(4, Material.CLOCK), layout.entries().get(Action.PAUSE));
        assertEquals(new ControlLayout.Entry(5, Material.SPECTRAL_ARROW), layout.entries().get(Action.FORWARD));
        assertEquals(new ControlLayout.Entry(6, Material.SUGAR), layout.entries().get(Action.SPEED));
        assertEquals(new ControlLayout.Entry(8, Material.BARRIER), layout.entries().get(Action.LEAVE));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void badMaterialFallsBackToTheDefault() throws Exception {
        YamlConfiguration config = bundled();
        config.set("CONTROLS.BACK.MATERIAL", "NOT_A_MATERIAL");

        ControlLayout layout = parse(config);

        assertEquals(new ControlLayout.Entry(3, Material.ARROW), layout.entries().get(Action.BACK));
        assertEquals(1, warnings.size());
    }

    @Test
    void outOfRangeSlotFallsBackToTheDefault() throws Exception {
        YamlConfiguration config = bundled();
        config.set("CONTROLS.BACK.SLOT", 9);

        ControlLayout layout = parse(config);

        assertEquals(3, layout.entries().get(Action.BACK).slot());
        assertEquals(1, warnings.size());
    }

    @Test
    void duplicateSlotFallsBackToTheDefault() throws Exception {
        YamlConfiguration config = bundled();
        config.set("CONTROLS.FOLLOW.SLOT", 3);

        ControlLayout layout = parse(config);

        assertEquals(3, layout.entries().get(Action.BACK).slot());
        assertEquals(0, layout.entries().get(Action.FOLLOW).slot());
        assertEquals(1, warnings.size());
    }

    @Test
    void actionWhoseDefaultSlotIsTakenIsLeftOut() throws Exception {
        YamlConfiguration config = bundled();
        config.set("CONTROLS.BACK.SLOT", 0);

        ControlLayout layout = parse(config);

        assertEquals(0, layout.entries().get(Action.BACK).slot());
        assertNull(layout.entries().get(Action.FOLLOW));
        assertEquals(2, warnings.size());
    }

    @Test
    void disabledActionIsAbsent() throws Exception {
        YamlConfiguration config = bundled();
        config.set("CONTROLS.NOCLIP.ENABLED", false);

        ControlLayout layout = parse(config);

        assertFalse(layout.entries().containsKey(Action.NOCLIP));
        assertEquals(6, layout.entries().size());
        assertTrue(warnings.isEmpty());
    }

    private ControlLayout parse(YamlConfiguration config) throws Exception {
        return ControlLayout.from(config, bundled(), ANY_ITEM, warnings::add);
    }

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = ControlLayoutTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return config;
    }
}
