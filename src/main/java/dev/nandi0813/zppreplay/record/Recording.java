package dev.nandi0813.zppreplay.record;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.nandi0813.api.Interface.Match;
import dev.nandi0813.zppreplay.format.ReplayFormat;
import dev.nandi0813.zppreplay.format.ReplayWriter;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.BoundingBox;

/** The recording of one match. Every method runs on the main thread; the writer thread only gets the finished body. */
public final class Recording {

    private static final double MOVE_EPSILON_SQUARED = 1e-6; // 0.001 blocks
    // shortcut: a slot changed without an equipment event is seen by the round-robin check within 60 ticks, not on the tick it changed
    private static final int EQUIP_CHECK_INTERVAL = 10;
    // shortcut: bot bodies are looked up every 20 ticks, not every tick, because getBotBodies allocates; a late body misses up to 1 s of frames, upgrade if the playtest sees it
    private static final int DISCOVERY_INTERVAL = 20;
    private static final int SLOTS = 6;
    /** One bit per recorded equipment slot: main hand, off hand, boots, leggings, chestplate, helmet. */
    static final int MAIN_HAND = 1;
    static final int OFF_HAND = 1 << 1;
    static final int ALL_SLOTS = (1 << SLOTS) - 1;
    private static final int DEFAULT_SPLASH_RGB = 0x385DC6;
    private static final byte[] NO_ITEM = new byte[0];
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};

    private final Match match;
    private final World world;
    private final BoundingBox bounds;
    private final boolean build;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final int blockOriginX;
    private final int blockOriginY;
    private final int blockOriginZ;
    private final int startTick;
    private final int maxDurationTicks;
    private final boolean showDeadSpectators;
    private final long startedAtMillis;
    private final String matchId;
    private final String ladder;
    private final String type;
    private final String originalArena;
    private final String arena;
    private final List<UUID> participants = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Map<UUID, Integer> ownerLimits;
    private final RecordProfiler profiler;
    private final ReplayWriter writer = new ReplayWriter();
    private final Map<UUID, Actor> actorsById = new HashMap<>();
    private final List<Actor> actors = new ArrayList<>();
    private final Map<String, Integer> palette = new HashMap<>();
    private final Map<BlockData, Integer> paletteByData = new HashMap<>();
    private final Set<Long> dirtyBlocks = new HashSet<>();
    private final Set<Long> touched = new HashSet<>();
    private final Map<UUID, Tracked> trackedById = new HashMap<>();
    private final List<Tracked> tracked = new ArrayList<>();
    private int lastStamp;
    private int nextDiscovery;
    private int nextEntityId;
    private boolean timed;
    private long equipNanos;

    /** A player or bot body. Its index is the actor id in the file. */
    private static final class Actor {
        final int index;
        Player player;
        boolean visible = true;
        boolean positioned;
        double x;
        double y;
        double z;
        byte yaw;
        byte pitch;
        byte headYaw;
        int flags = -1;
        int pose = -1;
        boolean equipKnown;
        int equipDirtyMask = ALL_SLOTS;
        int equipCursor;
        final ItemStack[] equipSeen = new ItemStack[SLOTS]; // copies of the last written items, null for empty

        Actor(int index, Player player) {
            this.index = index;
            this.player = player;
        }
    }

    /** An item, thrown potion, projectile, TNT or falling block. */
    private final class Tracked {
        final int id;
        final Entity entity;
        double x;
        double y;
        double z;
        byte yaw;
        byte pitch;

        Tracked(int id, Entity entity) {
            this.id = id;
            this.entity = entity;
        }

        /** Reads the entity position; returns true when it moved or turned. */
        boolean sample() {
            double nx = entity.getX() - originX;
            double ny = entity.getY() - originY;
            double nz = entity.getZ() - originZ;
            byte nyaw = ReplayFormat.angle(entity.getYaw());
            byte npitch = ReplayFormat.angle(entity.getPitch());
            double dx = nx - x;
            double dy = ny - y;
            double dz = nz - z;
            boolean changed = dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQUARED || nyaw != yaw || npitch != pitch;
            x = nx;
            y = ny;
            z = nz;
            yaw = nyaw;
            pitch = npitch;
            return changed;
        }
    }

    public Recording(Match match, int now, int maxDurationTicks, Map<UUID, Integer> ownerLimits) {
        this(match, now, maxDurationTicks, false, ownerLimits, null);
    }

    public Recording(Match match, int now, int maxDurationTicks, boolean showDeadSpectators,
                     Map<UUID, Integer> ownerLimits, RecordProfiler profiler) {
        this.profiler = profiler;
        this.showDeadSpectators = showDeadSpectators;
        this.match = match;
        this.world = match.getArenaWorld();
        this.bounds = match.getArenaBounds();
        this.build = match.isBuildArena();
        this.originX = bounds.getMinX();
        this.originY = bounds.getMinY();
        this.originZ = bounds.getMinZ();
        this.blockOriginX = (int) Math.floor(originX);
        this.blockOriginY = (int) Math.floor(originY);
        this.blockOriginZ = (int) Math.floor(originZ);
        this.startTick = now;
        this.lastStamp = now;
        this.nextDiscovery = now;
        this.maxDurationTicks = maxDurationTicks;
        this.startedAtMillis = System.currentTimeMillis();
        this.matchId = match.getMatchId();
        this.ladder = Objects.requireNonNullElse(match.getLadderName(), "");
        this.type = match.getMatchType().name();
        this.originalArena = match.getOriginalArenaName();
        this.arena = match.getArenaName();
        this.ownerLimits = Map.copyOf(ownerLimits);
        for (Player human : match.getPlayers()) {
            participants.add(human.getUniqueId());
            names.add(human.getName());
        }
    }

    public Match match() {
        return match;
    }

    public int startTick() {
        return startTick;
    }

    public int maxDurationTicks() {
        return maxDurationTicks;
    }

    public boolean hasActor(UUID player) {
        return actorsById.containsKey(player);
    }

    public int actorCount() {
        return actors.size();
    }

    /** Raw body bytes written so far. */
    public int bufferSize() {
        return writer.size();
    }

    /** Once per tick: new actors, positions, states, equipment, hidden bodies, tracked entities and dirty blocks. */
    public void tick(int now) throws IOException {
        timed = profiler != null && profiler.enabled();
        long mark = timed ? System.nanoTime() : 0L;
        if (now >= nextDiscovery) discover(now);
        mark = lap(RecordProfiler.DISCOVER, mark);
        for (int i = 0; i < actors.size(); i++) tickActor(actors.get(i), now);
        if (timed) {
            long end = System.nanoTime();
            profiler.phase(RecordProfiler.ACTORS, end - mark - equipNanos);
            profiler.phase(RecordProfiler.EQUIP, equipNanos);
            equipNanos = 0L;
            mark = end;
        }
        tickEntities(now);
        mark = lap(RecordProfiler.ENTITIES, mark);
        flushBlocks(now);
        lap(RecordProfiler.BLOCKS, mark);
    }

    /** Dev profiler only: records the time since {@code since} under {@code phase} and returns the new mark. */
    private long lap(int phase, long since) {
        if (!timed) return 0L;
        long end = System.nanoTime();
        profiler.phase(phase, end - since);
        return end;
    }

    public void roundStarted(int round, int now) throws IOException {
        discover(now);
        stamp(now);
        writer.writeRound(round);
        // core rolls the arena back between rounds without block events, so re-read every block written so far
        dirtyBlocks.addAll(touched);
        for (Actor actor : actors) {
            if (!actor.visible) show(actor, now);
        }
    }

    public void victimDied(UUID victim, int now) throws IOException {
        if (showDeadSpectators) return;
        Actor actor = actorsById.get(victim);
        if (actor == null || !actor.visible) return;
        actor.visible = false;
        stamp(now);
        writer.writeVisible(actor.index, false);
    }

    /** A dead player got a kit again after a temporary death: shows the hidden body. Rounds use {@link #roundStarted}. */
    public void respawned(UUID player, int now) throws IOException {
        Actor actor = actorsById.get(player);
        if (actor != null && !actor.visible) show(actor, now);
    }

    public void swing(UUID player, byte hand, int now) throws IOException {
        Actor actor = actorsById.get(player);
        if (actor == null) return;
        stamp(now);
        writer.writeSwing(actor.index, hand);
    }

    public void hurt(UUID player, int now) throws IOException {
        Actor actor = actorsById.get(player);
        if (actor == null) return;
        stamp(now);
        writer.writeHurt(actor.index);
    }

    /** Equipment events named these slots (bits of {@link #MAIN_HAND}..{@link #ALL_SLOTS}); checked on the next tick. */
    public void equipChanged(UUID player, int slotMask) {
        Actor actor = actorsById.get(player);
        if (actor != null) actor.equipDirtyMask |= slotMask;
    }

    /** A PlayerInventory slot index changed; only the recorded slot it names is marked, else nothing. */
    public void equipSlotChanged(UUID player, int inventorySlot) {
        Actor actor = actorsById.get(player);
        if (actor == null) return;
        int slot = recordedSlot(actor.player.getInventory().getHeldItemSlot(), inventorySlot);
        if (slot >= 0) actor.equipDirtyMask |= 1 << slot;
    }

    /** Maps a PlayerInventory index to a recorded slot (0..5 as in {@link #equipped}), or -1 when none. */
    static int recordedSlot(int heldSlot, int inventorySlot) {
        if (inventorySlot == heldSlot) return 0;
        return switch (inventorySlot) {
            case 40 -> 1;
            case 36 -> 2;
            case 37 -> 3;
            case 38 -> 4;
            case 39 -> 5;
            default -> -1;
        };
    }

    public void blockChanged(Block block) {
        if (!world.equals(block.getWorld())) return;
        if (!bounds.contains(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5)) return;
        dirtyBlocks.add(pack(block.getX() - blockOriginX, block.getY() - blockOriginY, block.getZ() - blockOriginZ));
    }

    /** Marks the block and its six face neighbours, for attached blocks that break without an event. */
    public void blockChangedAround(Block block) {
        blockChanged(block);
        for (BlockFace face : FACES) blockChanged(block.getRelative(face));
    }

    public void projectileLaunched(Projectile projectile, int now) throws IOException {
        if (!(projectile.getShooter() instanceof Player shooter)) return;
        if (!actorsById.containsKey(shooter.getUniqueId())) return;
        byte[] item = projectile instanceof ThrownPotion potion ? itemBytes(potion.getItem()) : NO_ITEM;
        track(projectile, item, now);
    }

    public void itemDropped(UUID player, Item item, int now) throws IOException {
        if (!actorsById.containsKey(player)) return;
        track(item, itemBytes(item.getItemStack()), now);
    }

    public void entitySpawned(Entity entity, int now) throws IOException {
        boolean physical = entity instanceof TNTPrimed || entity instanceof FallingBlock;
        if (!(entity instanceof EnderCrystal) && !(build && physical)) return;
        if (!world.equals(entity.getWorld()) || !bounds.contains(entity.getX(), entity.getY(), entity.getZ())) return;
        track(entity, NO_ITEM, now);
    }

    public void potionSplashed(ThrownPotion potion, int now) throws IOException {
        if (!trackedById.containsKey(potion.getUniqueId())) return;
        stamp(now);
        writer.writeEffect(0, (float) (potion.getX() - originX), (float) (potion.getY() - originY),
                (float) (potion.getZ() - originZ), splashColor(potion));
    }

    public void exploded(Entity entity, int now) throws IOException {
        if (entity == null) return;
        explodedAt(entity.getLocation(), now);
    }

    public void explodedAt(Location at, int now) throws IOException {
        if (!world.equals(at.getWorld()) || !bounds.contains(at.getX(), at.getY(), at.getZ())) return;
        stamp(now);
        writer.writeEffect(1, (float) (at.getX() - originX), (float) (at.getY() - originY),
                (float) (at.getZ() - originZ), 0);
    }

    /** Final tick, END, then hands the body to the store on the writer thread. */
    public void finish(int now, ReplayStore store) throws IOException {
        tick(now);
        writer.writeEnd();
        int duration = now - startTick;
        String id = store.newId();
        ReplayWriter.Header header = new ReplayWriter.Header(id, matchId, ladder, type, originalArena, arena,
                startedAtMillis, duration, build, bounds.getWidthX(), bounds.getHeight(), bounds.getWidthZ());
        ReplayStore.Entry entry = new ReplayStore.Entry(id, matchId, ladder, type, arena, startedAtMillis, duration,
                List.copyOf(participants), List.copyOf(names), new ArrayList<>(ownerLimits.keySet()));
        store.save(header, writer.body(), entry, ownerLimits);
    }

    private void discover(int now) throws IOException {
        nextDiscovery = now + DISCOVERY_INTERVAL;
        for (Player human : match.getPlayers()) announce(human, false, now);
        for (Player body : match.getBotBodies()) announce(body, true, now);
    }

    private void announce(Player player, boolean bot, int now) throws IOException {
        Actor known = actorsById.get(player.getUniqueId());
        if (known != null) {
            known.player = player;
            return;
        }
        Actor actor = new Actor(actors.size(), player);
        actorsById.put(player.getUniqueId(), actor);
        actors.add(actor);
        String skin = "";
        String signature = "";
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (!"textures".equals(property.getName())) continue;
            skin = Objects.requireNonNullElse(property.getValue(), "");
            signature = Objects.requireNonNullElse(property.getSignature(), "");
            break;
        }
        stamp(now);
        writer.writeActor(actor.index, player.getUniqueId().toString(), player.getName(), bot, skin, signature);
    }

    private void tickActor(Actor actor, int now) throws IOException {
        Player player = actor.player;
        if (!player.isValid()) return;

        double x = player.getX() - originX;
        double y = player.getY() - originY;
        double z = player.getZ() - originZ;
        byte yaw = ReplayFormat.angle(player.getBodyYaw());
        byte pitch = ReplayFormat.angle(player.getPitch());
        byte headYaw = ReplayFormat.angle(player.getYaw());
        double dx = x - actor.x;
        double dy = y - actor.y;
        double dz = z - actor.z;
        if (!actor.positioned || dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQUARED
                || yaw != actor.yaw || pitch != actor.pitch || headYaw != actor.headYaw) {
            stamp(now);
            writer.writePos(actor.index, (float) x, (float) y, (float) z, yaw, pitch, headYaw);
            actor.x = x;
            actor.y = y;
            actor.z = z;
            actor.yaw = yaw;
            actor.pitch = pitch;
            actor.headYaw = headYaw;
            actor.positioned = true;
        }

        int flags = flags(player);
        int pose = player.getPose().ordinal();
        if (flags != actor.flags || pose != actor.pose) {
            stamp(now);
            writer.writeState(actor.index, (byte) flags, (byte) pose);
            actor.flags = flags;
            actor.pose = pose;
        }

        if (actor.equipDirtyMask != 0 || now % EQUIP_CHECK_INTERVAL == 0) {
            long started = timed ? System.nanoTime() : 0L;
            checkEquip(actor, player, now);
            if (timed) equipNanos += System.nanoTime() - started;
        }
    }

    private void show(Actor actor, int now) throws IOException {
        actor.visible = true;
        stamp(now);
        writer.writeVisible(actor.index, true);
    }

    /** Checks the marked slots, then one slot in round-robin order every {@link #EQUIP_CHECK_INTERVAL} ticks. */
    private void checkEquip(Actor actor, Player player, int now) throws IOException {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SLOTS; slot++) {
            if ((actor.equipDirtyMask & (1 << slot)) != 0) equipSlot(actor, inventory, slot, now);
        }
        actor.equipDirtyMask = 0;
        actor.equipKnown = true;
        if (now % EQUIP_CHECK_INTERVAL != 0) return;
        equipSlot(actor, inventory, actor.equipCursor, now);
        actor.equipCursor = (actor.equipCursor + 1) % SLOTS;
    }

    private void equipSlot(Actor actor, PlayerInventory inventory, int slot, int now) throws IOException {
        ItemStack item = equipped(inventory, slot);
        if (item != null && item.getType().isAir()) item = null;
        if (actor.equipKnown && sameItem(actor.equipSeen[slot], item)) return;
        ItemStack seen = actor.equipSeen[slot];
        actor.equipSeen[slot] = item == null ? null : item.clone();
        stamp(now);
        if (actor.equipKnown && patchable(seen, item)) {
            writer.writeEquipPatch(actor.index, (byte) slot, item.getAmount(), damageField(item));
        } else {
            writer.writeEquip(actor.index, (byte) slot, itemBytes(item));
        }
    }

    /** isSimilar ignores the amount, so it is compared too. hashCode walked every component and dominated the profile. */
    static boolean sameItem(ItemStack seen, ItemStack item) {
        if (seen == null || item == null) return seen == item;
        return seen.getAmount() == item.getAmount() && seen.isSimilar(item);
    }

    /** Only the amount or DAMAGE differ, so playback can rebuild the item from the previous one. */
    static boolean patchable(ItemStack seen, ItemStack item) {
        // built here, not in a static field: DataComponentTypes needs a Paper registry, and tests load this class
        return seen != null && item != null && seen.matchesWithoutData(item, Set.of(DataComponentTypes.DAMAGE), true);
    }

    static int damageField(ItemStack item) {
        Integer damage = item.getData(DataComponentTypes.DAMAGE);
        return damage == null ? 0 : damage + 1;
    }

    private void tickEntities(int now) throws IOException {
        for (int i = tracked.size() - 1; i >= 0; i--) {
            Tracked entry = tracked.get(i);
            if (!entry.entity.isValid()) {
                stamp(now);
                writer.writeEntityRemove(entry.id);
                trackedById.remove(entry.entity.getUniqueId());
                tracked.remove(i);
            } else if (entry.sample()) {
                stamp(now);
                writer.writeEntityMove(entry.id, (float) entry.x, (float) entry.y, (float) entry.z, entry.yaw, entry.pitch);
            }
        }
    }

    private void flushBlocks(int now) throws IOException {
        if (dirtyBlocks.isEmpty()) return;
        for (long packed : dirtyBlocks) {
            short x = (short) (packed >>> 32);
            short y = (short) (packed >>> 16);
            short z = (short) packed;
            BlockData data = world.getBlockAt(x + blockOriginX, y + blockOriginY, z + blockOriginZ).getBlockData();
            int index = paletteIndex(data, now);
            stamp(now);
            writer.writeBlock(x, y, z, index);
            touched.add(packed);
        }
        dirtyBlocks.clear();
    }

    /** Skips building the state string for a block state already seen. */
    private int paletteIndex(BlockData data, int now) throws IOException {
        Integer known = paletteByData.get(data);
        if (known != null) return known;
        int index = paletteIndex(data.getAsString(), now);
        paletteByData.put(data, index);
        return index;
    }

    private int paletteIndex(String data, int now) throws IOException {
        Integer known = palette.get(data);
        if (known != null) return known;
        int created = palette.size();
        palette.put(data, created);
        stamp(now);
        writer.writePalette(created, data);
        return created;
    }

    private void track(Entity entity, byte[] item, int now) throws IOException {
        if (trackedById.containsKey(entity.getUniqueId())) return;
        Tracked entry = new Tracked(nextEntityId++, entity);
        entry.sample();
        tracked.add(entry);
        trackedById.put(entity.getUniqueId(), entry);
        stamp(now);
        writer.writeEntitySpawn(entry.id, entity.getType().getKey().toString(), (float) entry.x, (float) entry.y,
                (float) entry.z, entry.yaw, entry.pitch, item);
    }

    /** Writes TICK before the first record of a new tick only. */
    private void stamp(int now) throws IOException {
        if (now <= lastStamp) return;
        writer.writeTick(now - lastStamp);
        lastStamp = now;
    }

    private static int flags(Player player) {
        int flags = 0;
        boolean raised = player.isHandRaised();
        if (player.isSneaking()) flags |= 1;
        if (player.isSprinting()) flags |= 1 << 1;
        if (player.isSwimming()) flags |= 1 << 2;
        if (player.isGliding()) flags |= 1 << 3;
        if (raised) flags |= 1 << 4;
        if (raised && player.getHandRaised() == EquipmentSlot.OFF_HAND) flags |= 1 << 5;
        if (player.isVisualFire()) flags |= 1 << 6;
        if (player.isInvisible()) flags |= 1 << 7;
        return flags;
    }

    private static ItemStack equipped(PlayerInventory inventory, int slot) {
        return switch (slot) {
            case 0 -> inventory.getItemInMainHand();
            case 1 -> inventory.getItemInOffHand();
            case 2 -> inventory.getBoots();
            case 3 -> inventory.getLeggings();
            case 4 -> inventory.getChestplate();
            default -> inventory.getHelmet();
        };
    }

    private static byte[] itemBytes(ItemStack item) {
        return item == null || item.getType().isAir() ? NO_ITEM : item.serializeAsBytes();
    }

    private static int splashColor(ThrownPotion potion) {
        PotionMeta meta = potion.getPotionMeta();
        if (meta != null && meta.hasColor()) return meta.getColor().asRGB();
        Collection<PotionEffect> effects = potion.getEffects();
        if (!effects.isEmpty()) return effects.iterator().next().getType().getColor().asRGB();
        return DEFAULT_SPLASH_RGB;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFF);
    }
}
