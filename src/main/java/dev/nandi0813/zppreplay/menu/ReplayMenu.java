package dev.nandi0813.zppreplay.menu;

import dev.nandi0813.api.ZonePracticeApi;
import dev.nandi0813.zppreplay.ZPPReplay;
import dev.nandi0813.zppreplay.config.Messages;
import dev.nandi0813.zppreplay.play.PlaybackManager;
import dev.nandi0813.zppreplay.play.ReplayClock;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Paged list of replays, 28 per page in the inner 7x4 area inside a glass border, with a bottom bar for
 * previous, page and next. Every click is cancelled; a click on a replay starts it.
 */
public final class ReplayMenu implements InventoryHolder {

    private static final int PAGE_SIZE = 28;
    private static final int PREVIOUS_SLOT = 47;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 51;
    private static final int MAX_PLAYER_LINES = 8;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ZPPReplay plugin;
    private final PlaybackManager playback;
    private final List<ReplayStore.Entry> entries;
    private final Inventory inventory;
    private int page;

    public ReplayMenu(ZPPReplay plugin, PlaybackManager playback, List<ReplayStore.Entry> entries) {
        this.plugin = plugin;
        this.playback = playback;
        this.entries = List.copyOf(entries);
        this.inventory = Bukkit.createInventory(this, 54, plugin.getMessages().get("REPLAY.MENU-TITLE"));
        draw();
    }

    /** Menu slot of the entry at {@code index} within a page (0..27). */
    static int slot(int index) {
        return (1 + index / 7) * 9 + 1 + index % 7;
    }

    /** Index within a page of a menu slot, or -1 when the slot is not in the inner 7x4 area. */
    static int index(int slot) {
        int row = slot / 9, col = slot % 9;
        if (row < 1 || row > 4 || col < 1 || col > 7) return -1;
        return (row - 1) * 7 + col - 1;
    }

    /** Number of pages for the entries; an empty list still shows one page. */
    static int pages(int entries) {
        return Math.max(1, (entries + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void draw() {
        inventory.clear();
        for (int slot = 0; slot < 54; slot++) {
            int row = slot / 9, col = slot % 9;
            if (row == 0 || row == 5 || col == 0 || col == 8) inventory.setItem(slot, filler());
        }
        int first = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && first + i < entries.size(); i++) {
            inventory.setItem(slot(i), item(entries.get(first + i)));
        }
        if (page > 0) inventory.setItem(PREVIOUS_SLOT, control(Material.ARROW, "REPLAY.MENU-PREVIOUS"));
        if (first + PAGE_SIZE < entries.size()) inventory.setItem(NEXT_SLOT, control(Material.ARROW, "REPLAY.MENU-NEXT"));
        inventory.setItem(PAGE_SLOT, control(Material.BOOK, "REPLAY.MENU-PAGE",
                Placeholder.unparsed("page", String.valueOf(page + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages(entries.size())))));
    }

    private void click(Player player, int slot) {
        if (slot == PREVIOUS_SLOT && page > 0) {
            page--;
            draw();
            return;
        }
        if (slot == NEXT_SLOT && (page + 1) * PAGE_SIZE < entries.size()) {
            page++;
            draw();
            return;
        }
        int i = index(slot);
        if (i < 0) return;
        int idx = page * PAGE_SIZE + i;
        if (idx >= entries.size()) return;
        player.closeInventory();
        playback.play(player, entries.get(idx).id());
    }

    private ItemStack item(ReplayStore.Entry entry) {
        Messages messages = plugin.getMessages();
        ItemStack base = ZonePracticeApi.getInstance().getLadderIcon(entry.ladder());
        ItemStack item = base == null || base.getType() == Material.AIR ? new ItemStack(Material.PAPER) : base.clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_UNBREAKABLE);
        String date = DATE.format(Instant.ofEpochMilli(entry.startedAt()));
        List<String> names = entry.names();
        meta.displayName(plain(messages.get("REPLAY.MENU-ITEM-NAME",
                Placeholder.unparsed("ladder", entry.ladder()), Placeholder.unparsed("date", date),
                Placeholder.unparsed("type", entry.type()))));

        List<Component> lore = new ArrayList<>(messages.getList("REPLAY.MENU-ITEM-LORE",
                Placeholder.unparsed("duration", ReplayClock.format(entry.durationTicks())),
                Placeholder.unparsed("players", String.join(", ", names)),
                Placeholder.unparsed("type", entry.type()),
                Placeholder.unparsed("arena", entry.arena()),
                Placeholder.unparsed("date", date)));
        for (int i = 0; i < names.size() && i < MAX_PLAYER_LINES; i++) {
            lore.add(messages.get("REPLAY.MENU-ITEM-PLAYER", Placeholder.unparsed("name", names.get(i))));
        }
        if (names.size() > MAX_PLAYER_LINES) {
            lore.add(messages.get("REPLAY.MENU-ITEM-MORE",
                    Placeholder.unparsed("count", String.valueOf(names.size() - MAX_PLAYER_LINES))));
        }
        lore.addAll(messages.getList("REPLAY.MENU-ITEM-FOOTER"));
        meta.lore(lore.stream().map(ReplayMenu::plain).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack control(Material material, String path, TagResolver... resolvers) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(plugin.getMessages().get(path, resolvers)));
        item.setItemMeta(meta);
        return item;
    }

    /** Border pane: no name and no tooltip, so it reads as frame only. */
    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Cancels every click and drag in the menu and hands clicks on replays to the menu. */
    public static final class Clicks implements Listener {

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder(false) instanceof ReplayMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() != menu.inventory) return;
            menu.click(player, event.getSlot());
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder(false) instanceof ReplayMenu) event.setCancelled(true);
        }
    }
}
