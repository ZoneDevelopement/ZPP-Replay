package dev.nandi0813.zppreplay.play;

import dev.nandi0813.zppreplay.ZPPReplay;
import dev.nandi0813.zppreplay.config.ControlLayout;
import dev.nandi0813.zppreplay.config.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Hotbar items of a viewer, tagged with {@code zppreplay:control}. Clicks run the action on the viewer's session;
 * a double tap of sneak leaves noclip. The viewer cannot change the inventory, drop, take damage or hunger, pick up or change blocks.
 */
public final class Controls implements Listener {

    /** Slots and items come from config.yml CONTROLS; the order decides which action keeps a slot both want. */
    public enum Action {
        BACK, PAUSE, FORWARD, SPEED, FOLLOW, NOCLIP, LEAVE
    }

    private final NamespacedKey key;
    private final Messages messages;
    private final Function<UUID, ViewerSession> sessions;
    private final ControlLayout layout;

    public Controls(ZPPReplay plugin, Function<UUID, ViewerSession> sessions) {
        this.key = new NamespacedKey(plugin, "control");
        this.messages = plugin.getMessages();
        this.sessions = sessions;
        this.layout = ControlLayout.from(plugin.getConfig(), plugin.getConfig().getDefaults(), Material::isItem,
                warning -> plugin.getLogger().warning(warning));
    }

    /** Puts the enabled control items into their slots, named for the session's current state. */
    void give(Player viewer, ViewerSession session) {
        String followed = session.followedName();
        TagResolver state = TagResolver.resolver(
                Placeholder.component("state", messages.get(session.paused() ? "REPLAY.CONTROL-PAUSED" : "REPLAY.CONTROL-PLAYING")),
                Placeholder.unparsed("speed", session.speedLabel()),
                followed == null ? Placeholder.component("target", messages.get("REPLAY.CONTROL-NO-TARGET"))
                        : Placeholder.unparsed("target", followed));
        layout.entries().forEach((action, entry) ->
                viewer.getInventory().setItem(entry.slot(), item(action, entry.material(), state)));
    }

    private ItemStack item(Action action, Material material, TagResolver state) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        String path = "REPLAY.CONTROLS." + action.name();
        meta.displayName(messages.get(path + ".NAME", state).decorationIfAbsent(TextDecoration.ITALIC,
                TextDecoration.State.FALSE));
        List<Component> lore = new ArrayList<>();
        for (Component line : messages.getList(path + ".LORE", state)) {
            lore.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action.name());
        item.setItemMeta(meta);
        return item;
    }

    private Action actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String name = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (name == null) return null;
        for (Action action : Action.values()) {
            if (action.name().equals(name)) return action;
        }
        return null;
    }

    private boolean watching(Entity entity) {
        return entity instanceof Player player && sessions.apply(player.getUniqueId()) != null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ViewerSession session = sessions.apply(event.getPlayer().getUniqueId());
        if (session == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = actionOf(event.getItem());
        if (action != null) session.perform(action);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity clicker = event.getWhoClicked();
        if (watching(clicker)) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (watching(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (watching(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (watching(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (watching(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        LivingEntity picker = event.getEntity();
        if (watching(picker)) event.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (watching(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (watching(event.getPlayer())) event.setCancelled(true);
    }

    /** Spectators get no item clicks, so a double tap of sneak is the way back from noclip. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        ViewerSession session = sessions.apply(event.getPlayer().getUniqueId());
        if (session == null || !session.isNoclip()) return;
        if (event.isSneaking()) {
            session.sneakPressed(Bukkit.getCurrentTick());
        } else if (session.sneakReleased(Bukkit.getCurrentTick())) {
            session.toggleNoclip();
        }
    }
}
