package dev.nandi0813.zppreplay.record;

import dev.nandi0813.api.Event.FightDeathEvent;
import dev.nandi0813.api.Event.KitAppliedEvent;
import dev.nandi0813.api.Event.Match.BotMatchEndEvent;
import dev.nandi0813.api.Event.Match.MatchBlockChangeEvent;
import dev.nandi0813.api.Event.Match.MatchEndEvent;
import dev.nandi0813.api.Event.Match.MatchRoundStartEvent;
import dev.nandi0813.api.Event.Match.MatchStartEvent;
import dev.nandi0813.api.Interface.Match;
import dev.nandi0813.zppreplay.ZPPReplay;
import dev.nandi0813.zppreplay.config.ReplaySettings;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

/** Starts, feeds and saves one {@link Recording} per match, within the configured caps. Main thread only. */
public final class RecordingManager implements Listener {

    static final String RECORD_PERMISSION = "zppreplay.record";
    private static final String LIMIT_PREFIX = "zppreplay.limit.";
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 1000;

    private final ZPPReplay plugin;
    private final ReplaySettings settings;
    private final ReplayStore store;
    private final RecordProfiler profiler;
    private final Map<Match, Recording> recordings = new IdentityHashMap<>();
    private BukkitTask ticker;

    public RecordingManager(ZPPReplay plugin, ReplaySettings settings, ReplayStore store, RecordProfiler profiler) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.profiler = profiler;
    }

    /** Registers the listeners and the per-tick task. */
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    /** Stops the task and drops every recording in progress; nothing is saved. */
    public void discardAll() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        HandlerList.unregisterAll(this);
        recordings.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMatchStart(MatchStartEvent event) {
        Match match = event.getMatch();
        if (!settings.recordingEnabled() || recordings.containsKey(match)) return;
        if (recordings.size() >= settings.maxConcurrent()) return;
        if (match.getPlayers().size() + match.getBotBodies().size() > settings.maxPlayersPerMatch()) return;

        Map<UUID, Integer> owners = new LinkedHashMap<>();
        for (Player player : match.getPlayers()) {
            if (player.hasPermission(RECORD_PERMISSION)) owners.put(player.getUniqueId(), limitFor(player));
        }
        if (owners.isEmpty()) return;
        recordings.put(match, new Recording(match, Bukkit.getCurrentTick(), settings.maxDurationTicks(),
                settings.showDeadSpectators(), owners, profiler));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRoundStart(MatchRoundStartEvent event) {
        Recording recording = recordings.get(event.getMatch());
        if (recording == null) return;
        int round = event.getRound();
        guard(recording, () -> recording.roundStarted(round, Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFightDeath(FightDeathEvent event) {
        Match match = event.getMatch();
        Recording recording = match == null ? null : recordings.get(match);
        if (recording == null) return;
        UUID victim = event.getVictim().getUniqueId();
        guard(recording, () -> recording.victimDied(victim, Bukkit.getCurrentTick()));
    }

    // Not cancellable. Core applies a kit again after a temporary death, which shows the hidden body.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKitApplied(KitAppliedEvent event) {
        if (!(event.getFight() instanceof Match match)) return;
        Recording recording = recordings.get(match);
        if (recording == null) return;
        UUID player = event.getPlayer().getUniqueId();
        guard(recording, () -> recording.respawned(player, Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockChange(MatchBlockChangeEvent event) {
        Recording recording = recordings.get(event.getMatch());
        if (recording != null) recording.blockChanged(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMatchEnd(MatchEndEvent event) {
        finishMatch(event.getMatch());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotMatchEnd(BotMatchEndEvent event) {
        finishMatch(event.getMatch());
    }

    private void finishMatch(Match match) {
        Recording recording = recordings.remove(match);
        if (recording != null) finish(recording, Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        PlayerAnimationType animation = event.getAnimationType();
        if (animation != PlayerAnimationType.ARM_SWING && animation != PlayerAnimationType.OFF_ARM_SWING) return;
        byte hand = (byte) (animation == PlayerAnimationType.OFF_ARM_SWING ? 1 : 0);
        UUID player = event.getPlayer().getUniqueId();
        for (Recording recording : recordings.values()) {
            if (!recording.hasActor(player)) continue;
            guard(recording, () -> recording.swing(player, hand, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        UUID id = victim.getUniqueId();
        for (Recording recording : recordings.values()) {
            if (!recording.hasActor(id)) continue;
            guard(recording, () -> recording.hurt(id, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player shooter)) return;
        UUID id = shooter.getUniqueId();
        Projectile projectile = event.getEntity();
        for (Recording recording : recordings.values()) {
            if (!recording.hasActor(id)) continue;
            guard(recording, () -> recording.projectileLaunched(projectile, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        equipChanged(player);
        Item item = event.getItemDrop();
        for (Recording recording : recordings.values()) {
            if (!recording.hasActor(player)) continue;
            guard(recording, () -> recording.itemDropped(player, item, Bukkit.getCurrentTick()));
        }
    }

    // Equipment events mark the actor for a check of the slots they name; a slot changed without one is caught by the round-robin check.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        Player player = event.getPlayer();
        // getSlot() is a PlayerInventory index only while the player's own crafting view is open; otherwise check all
        if (player.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING) {
            UUID id = player.getUniqueId();
            for (Recording recording : recordings.values()) recording.equipSlotChanged(id, event.getSlot());
        } else {
            equipChanged(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        equipChanged(event.getPlayer().getUniqueId(), Recording.MAIN_HAND);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        equipChanged(event.getPlayer().getUniqueId(), Recording.MAIN_HAND | Recording.OFF_HAND);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        equipChanged(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) equipChanged(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        equipChanged(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        equipChanged(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof TNTPrimed || entity instanceof FallingBlock || entity instanceof EnderCrystal)) return;
        for (Recording recording : recordings.values()) {
            guard(recording, () -> recording.entitySpawned(entity, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        for (Recording recording : recordings.values()) {
            guard(recording, () -> recording.potionSplashed(event.getEntity(), Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        for (Block block : event.blockList()) markAround(block);
        for (Recording recording : recordings.values()) {
            guard(recording, () -> recording.exploded(entity, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (recordings.isEmpty()) return;
        markAround(event.getBlock());
        for (Block block : event.blockList()) markAround(block);
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        for (Recording recording : recordings.values()) {
            guard(recording, () -> recording.explodedAt(at, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        markAround(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        markAround(event.getBlockPlaced());
    }

    /** Right-clicks change block state (anchor charge, doors, trapdoors, levers, candles, cake) without a block event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        mark(event.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        mark(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        markAround(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        markAround(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        markAround(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        markPiston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        markPiston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    /** Highest valid {@code zppreplay.limit.<n>} among the granted permissions, else the fallback. */
    static int limitFor(Iterable<String> permissions, int fallback) {
        int best = 0;
        for (String permission : permissions) {
            if (!permission.startsWith(LIMIT_PREFIX)) continue;
            String digits = permission.substring(LIMIT_PREFIX.length());
            if (digits.isEmpty() || digits.length() > 4 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) continue;
            int value = Integer.parseInt(digits);
            if (value >= MIN_LIMIT && value <= MAX_LIMIT) best = Math.max(best, value);
        }
        return best > 0 ? best : fallback;
    }

    private int limitFor(Player player) {
        List<String> granted = new ArrayList<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue()) granted.add(info.getPermission());
        }
        return limitFor(granted, settings.defaultPerPlayer());
    }

    private void equipChanged(UUID player) {
        equipChanged(player, Recording.ALL_SLOTS);
    }

    private void equipChanged(UUID player, int slotMask) {
        for (Recording recording : recordings.values()) recording.equipChanged(player, slotMask);
    }

    private void mark(Block block) {
        if (recordings.isEmpty()) return;
        boolean profiled = profiler.enabled();
        long started = profiled ? System.nanoTime() : 0L;
        for (Recording recording : recordings.values()) recording.blockChanged(block);
        if (profiled) profiler.blockEvent(System.nanoTime() - started);
    }

    private void markAround(Block block) {
        if (recordings.isEmpty()) return;
        boolean profiled = profiler.enabled();
        long started = profiled ? System.nanoTime() : 0L;
        for (Recording recording : recordings.values()) recording.blockChangedAround(block);
        if (profiled) profiler.blockEvent(System.nanoTime() - started);
    }

    /** Pistons move their blocks over 2 ticks, so the moved blocks are read again after 3. */
    private void markPiston(Block piston, BlockFace direction, List<Block> pushed) {
        if (recordings.isEmpty()) return;
        List<Block> moved = new ArrayList<>();
        moved.add(piston);
        moved.add(piston.getRelative(direction));
        for (Block block : pushed) {
            moved.add(block);
            moved.add(block.getRelative(direction));
        }
        moved.forEach(this::mark);
        Bukkit.getScheduler().runTaskLater(plugin, () -> moved.forEach(this::mark), 3L);
    }

    private void tickAll() {
        int now = Bukkit.getCurrentTick();
        boolean profiled = profiler.enabled() && !recordings.isEmpty();
        long started = profiled ? System.nanoTime() : 0L;
        int actors = 0;
        if (profiled) for (Recording recording : recordings.values()) actors += recording.actorCount();
        Iterator<Recording> iterator = recordings.values().iterator();
        while (iterator.hasNext()) {
            Recording recording = iterator.next();
            try {
                if (now - recording.startTick() < recording.maxDurationTicks()) {
                    recording.tick(now);
                    continue;
                }
                iterator.remove();
                finish(recording, now);
            } catch (IOException | RuntimeException e) {
                iterator.remove();
                plugin.getLogger().warning("Recording of match " + recording.match().getMatchId() + " stopped: " + e);
            }
        }
        if (!profiled) return;
        profiler.tick(System.nanoTime() - started, actors);
        if (!profiler.full()) return;
        long bytes = 0;
        for (Recording recording : recordings.values()) bytes += recording.bufferSize();
        profiler.report(recordings.size(), bytes);
    }

    private void finish(Recording recording, int now) {
        try {
            recording.finish(now, store);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Recording of match " + recording.match().getMatchId() + " was not saved: " + e);
        }
    }

    private void guard(Recording recording, IoAction action) {
        try {
            action.run();
        } catch (IOException | RuntimeException e) {
            recordings.remove(recording.match());
            plugin.getLogger().warning("Recording of match " + recording.match().getMatchId() + " stopped: " + e);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
