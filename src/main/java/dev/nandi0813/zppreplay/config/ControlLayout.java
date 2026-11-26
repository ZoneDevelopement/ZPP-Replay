package dev.nandi0813.zppreplay.config;

import dev.nandi0813.zppreplay.play.Controls;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Slots and items of the viewer controls from config.yml CONTROLS. A bad value logs one warning and falls back to the
 * bundled default; an action whose default slot is taken as well is left out. Disabled actions are absent. Pure Java.
 */
public record ControlLayout(Map<Controls.Action, Entry> entries) {

    /** Hotbar slot and item of one control. */
    public record Entry(int slot, Material material) {
    }

    /**
     * {@code defaults} is the bundled config.yml. {@code isItem} is {@code Material::isItem} in game: it needs a running
     * server, so the parse takes it as a parameter. {@code warn} gets one line per bad value.
     */
    public static ControlLayout from(ConfigurationSection config, ConfigurationSection defaults, Predicate<Material> isItem,
                                     Consumer<String> warn) {
        Map<Controls.Action, Entry> entries = new EnumMap<>(Controls.Action.class);
        Set<Integer> taken = new HashSet<>();
        for (Controls.Action action : Controls.Action.values()) {
            String path = "CONTROLS." + action.name();
            if (!config.getBoolean(path + ".ENABLED", true)) continue;

            int defaultSlot = defaults.getInt(path + ".SLOT");
            int slot = config.getInt(path + ".SLOT", defaultSlot);
            if (slot < 0 || slot > 8 || taken.contains(slot)) {
                warn.accept(path + ".SLOT " + slot + " is outside 0-8 or taken, using " + defaultSlot);
                slot = defaultSlot;
            }
            if (taken.contains(slot)) {
                warn.accept(path + " is left out, its default slot " + slot + " is taken");
                continue;
            }

            Material material = itemOf(config.getString(path + ".MATERIAL"), isItem);
            if (material == null) {
                warn.accept(path + ".MATERIAL is not an item, using " + defaults.getString(path + ".MATERIAL"));
                material = itemOf(defaults.getString(path + ".MATERIAL"), isItem);
            }
            taken.add(slot);
            entries.put(action, new Entry(slot, material));
        }
        return new ControlLayout(entries);
    }

    private static Material itemOf(String name, Predicate<Material> isItem) {
        Material material = name == null ? null : Material.matchMaterial(name);
        return material != null && isItem.test(material) ? material : null;
    }
}
