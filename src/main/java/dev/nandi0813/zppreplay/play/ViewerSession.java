package dev.nandi0813.zppreplay.play;

import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import dev.nandi0813.api.Interface.ArenaLease;
import dev.nandi0813.zppreplay.ZPPReplay;
import dev.nandi0813.zppreplay.config.Messages;
import dev.nandi0813.zppreplay.format.ReplayFormat;
import dev.nandi0813.zppreplay.format.ReplayReader;
import dev.nandi0813.zppreplay.format.ReplayReader.Rec;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

/**
 * One viewer watching one replay in a leased arena. Records are applied up to the replay clock: blocks through
 * {@link Player#sendBlockChange}, bodies and entities through PacketEvents. Main thread only.
 */
public final class ViewerSession {

    /** How far (blocks) the viewer may leave the arena bounds before being brought back to the middle. */
    private static final double BOUNDS_MARGIN = 8;
    /** Step of the back and forward controls, 10 seconds. */
    private static final int SKIP_TICKS = 200;
    private static final int ROUND_BANNER_TICKS = 60;
    private static final int SPLASH_PARTICLES = 20;
    private static final int SLOTS = 6;
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.BOOTS, EquipmentSlot.LEGGINGS, EquipmentSlot.CHEST_PLATE, EquipmentSlot.HELMET};
    private static final Pose[] POSES = Pose.values();

    private final ZPPReplay plugin;
    private final PlaybackManager manager;
    private final Controls controls;
    private final Messages messages;
    private final Player viewer;
    private final ArenaLease lease;
    private final List<Rec> records;
    private final World world;
    private final BoundingBox expanded;
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final ReplayClock clock;
    private final Map<Integer, BlockData> palette = new HashMap<>();
    private final Set<Long> changed = new HashSet<>();
    private final Map<Integer, Body> bodies = new LinkedHashMap<>();
    private final Map<Integer, Prop> props = new LinkedHashMap<>();
    private Saved saved;
    private int cursor;
    private int recordTick;
    private int followIndex = -1;
    private Body followed;
    private int round;
    private int roundBannerUntil = -1;
    private boolean finishedShown;
    private final SneakTaps sneakTaps = new SneakTaps();
    private BukkitTask task;

    /** The viewer as it was before the session. */
    private record Saved(Location location, GameMode mode, ItemStack[] contents, boolean allowFlight, boolean flying,
                         boolean invulnerable, int fireTicks, double health, int food) {
    }

    /** A player body. Hidden until its first position. */
    private static final class Body {
        final int id;
        final UUID uuid;
        final String name;
        final String skinValue;
        final String skinSignature;
        final ItemStack[] equipment = new ItemStack[SLOTS];
        boolean visible = true;
        boolean positioned;
        boolean inWorld;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        float headYaw;
        byte flags;
        byte hand;
        EntityPose pose = EntityPose.STANDING;

        Body(int id, Rec.Actor data) {
            this.id = id;
            this.uuid = UUID.randomUUID();
            this.name = data.name();
            this.skinValue = data.skinValue();
            this.skinSignature = data.skinSignature();
        }
    }

    /** An item, thrown potion, projectile, TNT or falling block. */
    private static final class Prop {
        final int id;
        final UUID uuid = UUID.randomUUID();
        final EntityType type;
        final ItemStack item;
        boolean inWorld;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;

        Prop(int id, EntityType type, ItemStack item) {
            this.id = id;
            this.type = type;
            this.item = item;
        }
    }

    public ViewerSession(ZPPReplay plugin, PlaybackManager manager, Controls controls, Player viewer, ArenaLease lease,
                         ReplayReader.Replay replay) {
        this.plugin = plugin;
        this.manager = manager;
        this.controls = controls;
        this.messages = plugin.getMessages();
        this.viewer = viewer;
        this.lease = lease;
        this.records = replay.records();
        this.world = lease.getWorld();
        BoundingBox bounds = lease.getBounds();
        this.expanded = bounds.clone().expand(BOUNDS_MARGIN);
        this.centerX = bounds.getCenterX();
        this.centerY = bounds.getCenterY();
        this.centerZ = bounds.getCenterZ();
        this.originX = bounds.getMinX();
        this.originY = bounds.getMinY();
        this.originZ = bounds.getMinZ();
        this.blockX = (int) Math.floor(originX);
        this.blockY = (int) Math.floor(originY);
        this.blockZ = (int) Math.floor(originZ);
        this.clock = new ReplayClock(replay.header().durationTicks());
    }

    public World world() {
        return world;
    }

    /** Takes over the viewer, hides the other players both ways and starts the tick task. */
    public void start() {
        saved = save();
        viewer.closeInventory();
        viewer.teleport(new Location(world, centerX, centerY, centerZ, viewer.getLocation().getYaw(),
                viewer.getLocation().getPitch()));
        viewer.setGameMode(GameMode.ADVENTURE);
        enableFlight();
        viewer.setInvulnerable(true);
        viewer.getInventory().clear();
        controls.give(viewer, this);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(viewer)) hideFrom(other);
        }
        advanceTo(clock.tick(), true);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSafely, 1L, 1L);
        // The client ignores ability packets until it acknowledges the teleport.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (task != null && viewer.getGameMode() != GameMode.SPECTATOR) enableFlight();
        });
    }

    private void enableFlight() {
        viewer.setAllowFlight(true);
        viewer.setFlying(true);
    }

    /** Spectator mode passes through blocks; switching back restores the adventure controls. */
    public void toggleNoclip() {
        if (isNoclip()) {
            viewer.setGameMode(GameMode.ADVENTURE);
            enableFlight();
            refresh();
            messages.send(viewer, "REPLAY.NOCLIP-OFF");
        } else {
            sneakTaps.reset();
            viewer.setGameMode(GameMode.SPECTATOR);
            // fade 5, stay 50 and fade 10 ticks
            viewer.showTitle(Title.title(messages.get("REPLAY.NOCLIP-TITLE"), messages.get("REPLAY.NOCLIP-SUBTITLE"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))));
            messages.send(viewer, "REPLAY.NOCLIP-ON");
        }
    }

    boolean isNoclip() {
        return viewer.getGameMode() == GameMode.SPECTATOR;
    }

    void sneakPressed(int now) {
        sneakTaps.press(now);
    }

    /** True on the release that completes a double tap of sneak; the caller then leaves noclip. */
    boolean sneakReleased(int now) {
        return sneakTaps.release(now);
    }

    /** Names the control items for the current state. Skipped in noclip, where the spectator hotbar shows no items. */
    private void refresh() {
        if (!isNoclip()) controls.give(viewer, this);
    }

    boolean paused() {
        return clock.paused();
    }

    String speedLabel() {
        return clock.speedLabel();
    }

    /** Name of the body the follow control jumped to last, null before the first jump. */
    String followedName() {
        return followed == null ? null : followed.name;
    }

    /** Hides both ways; used on start and for players who join while the session runs. */
    public void hideFrom(Player other) {
        viewer.hidePlayer(plugin, other);
        other.hidePlayer(plugin, viewer);
    }

    public void perform(Controls.Action action) {
        switch (action) {
            case BACK -> seek(clock.tick() - SKIP_TICKS);
            case FORWARD -> seek(clock.tick() + SKIP_TICKS);
            case PAUSE -> clock.togglePause();
            case SPEED -> clock.cycleSpeed();
            case FOLLOW -> follow();
            case NOCLIP -> toggleNoclip();
            case LEAVE -> manager.leave(viewer);
        }
        if (action != Controls.Action.LEAVE) refresh();
    }

    /** Moves the clock to {@code target}. Backwards, the session rebuilds the scene and respawns the bodies. */
    public void seek(int target) {
        clock.seek(target);
        int goal = clock.tick();
        if (goal < recordTick) {
            rewind();
            advanceTo(goal, false);
            respawnAll();
        } else {
            advanceTo(goal, true);
        }
    }

    /**
     * Stops the session: blocks and bodies are restored, the arena is released and the other players are shown again.
     * The viewer is restored; it is teleported back only when {@code teleportBack} is set.
     */
    public void end(boolean teleportBack) {
        if (task != null) task.cancel();
        task = null;
        boolean online = viewer.isOnline();
        if (online) {
            restoreBlocks();
            hideEntities();
        }
        lease.release();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer)) continue;
            if (online) viewer.showPlayer(plugin, other);
            if (!manager.isWatching(other.getUniqueId())) other.showPlayer(plugin, viewer);
        }
        if (online && saved != null) restoreViewer(teleportBack);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Replay session of " + viewer.getName() + " stopped: " + e);
            manager.end(viewer.getUniqueId(), true);
        }
    }

    private void tick() {
        clock.advance();
        advanceTo(clock.tick(), true);
        if (clock.finished() && !finishedShown) {
            finishedShown = true;
            clock.setPaused(true);
            refresh();
            messages.send(viewer, "REPLAY.ENDED");
        }
        keepInBounds();
        actionBar();
    }

    private void keepInBounds() {
        Location at = viewer.getLocation();
        if (at.getWorld() == world && expanded.contains(at.getX(), at.getY(), at.getZ())) return;
        viewer.teleport(new Location(world, centerX, centerY, centerZ, at.getYaw(), at.getPitch()));
    }

    private void actionBar() {
        Component line = clock.tick() < roundBannerUntil
                ? messages.get("REPLAY.ROUND", Placeholder.unparsed("round", String.valueOf(round)))
                : messages.get(isNoclip() ? "REPLAY.ACTION-BAR-NOCLIP" : "REPLAY.ACTION-BAR",
                        Placeholder.unparsed("time", ReplayClock.format(clock.tick())),
                        Placeholder.unparsed("total", ReplayClock.format(clock.duration())),
                        Placeholder.unparsed("speed", clock.speedLabel()));
        viewer.sendActionBar(line);
    }

    /** Applies the records up to {@code target}. {@code send} false only rebuilds state (blocks are always sent). */
    private void advanceTo(int target, boolean send) {
        int stop = ReplayClock.cursorAt(records, cursor, recordTick, target);
        for (; cursor < stop; cursor++) {
            Rec rec = records.get(cursor);
            if (rec instanceof Rec.Tick tick) recordTick += tick.delta();
            else apply(rec, send);
        }
    }

    private void apply(Rec rec, boolean send) {
        switch (rec) {
            case Rec.Tick tick -> {
            }
            case Rec.Actor actor -> bodies.put(actor.index(), new Body(FakeIds.next(), actor));
            case Rec.Pos pos -> placeBody(pos, send);
            case Rec.State state -> applyState(state, send);
            case Rec.Swing swing -> swingBody(swing, send);
            case Rec.Hurt hurt -> hurtBody(hurt, send);
            case Rec.Equip equip -> equipBody(equip, send);
            case Rec.EquipPatch patch -> patchBody(patch, send);
            case Rec.Visible visible -> visibleBody(visible, send);
            case Rec.Palette entry -> palette.put(entry.index(), blockData(entry.blockData()));
            case Rec.Block block -> placeBlock(block);
            case Rec.EntitySpawn spawn -> spawnProp(spawn, send);
            case Rec.EntityMove move -> moveProp(move, send);
            case Rec.EntityRemove remove -> removeProp(remove);
            case Rec.Effect effect -> playEffect(effect, send);
            case Rec.Round roundRecord -> {
                round = roundRecord.round();
                roundBannerUntil = clock.tick() + ROUND_BANNER_TICKS;
            }
        }
    }

    private void placeBody(Rec.Pos pos, boolean send) {
        Body body = bodies.get(pos.actor());
        if (body == null) return;
        body.x = pos.x();
        body.y = pos.y();
        body.z = pos.z();
        body.yaw = ReplayFormat.angle(pos.yaw());
        body.pitch = ReplayFormat.angle(pos.pitch());
        body.headYaw = ReplayFormat.angle(pos.headYaw());
        body.positioned = true;
        if (!send) return;
        if (body.inWorld) {
            PacketOut.move(viewer, body.id, absX(body.x), absY(body.y), absZ(body.z), body.yaw, body.pitch);
            PacketOut.headRotation(viewer, body.id, body.headYaw);
        } else if (body.visible) {
            showBody(body);
        }
    }

    private void applyState(Rec.State state, boolean send) {
        Body body = bodies.get(state.actor());
        if (body == null) return;
        int flags = state.flags() & 0xFF;
        body.flags = vanillaFlags(flags);
        body.hand = handFlags(flags);
        body.pose = poseOf(state.pose());
        if (send && body.inWorld) PacketOut.state(viewer, body.id, body.flags, body.hand, body.pose);
    }

    private void swingBody(Rec.Swing swing, boolean send) {
        Body body = bodies.get(swing.actor());
        if (send && body != null && body.inWorld) PacketOut.swing(viewer, body.id, swing.hand());
    }

    private void hurtBody(Rec.Hurt hurt, boolean send) {
        Body body = bodies.get(hurt.actor());
        if (!send || body == null || !body.inWorld) return;
        PacketOut.hurt(viewer, body.id, body.yaw);
        viewer.playSound(new Location(world, absX(body.x), absY(body.y), absZ(body.z)), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
    }

    private void equipBody(Rec.Equip equip, boolean send) {
        Body body = bodies.get(equip.actor());
        int slot = equip.slot();
        if (body == null || slot < 0 || slot >= SLOTS) return;
        body.equipment[slot] = item(equip.item());
        if (send && body.inWorld) PacketOut.equipment(viewer, body.id, EQUIPMENT_SLOTS[slot], body.equipment[slot]);
    }

    private void patchBody(Rec.EquipPatch patch, boolean send) {
        Body body = bodies.get(patch.actor());
        int slot = patch.slot();
        if (body == null || slot < 0 || slot >= SLOTS || body.equipment[slot] == null) return;
        ItemStack item = body.equipment[slot].clone();
        item.setAmount(patch.amount());
        if (patch.damage() == 0) item.resetData(DataComponentTypes.DAMAGE);
        else item.setData(DataComponentTypes.DAMAGE, patch.damage() - 1);
        body.equipment[slot] = item;
        if (send && body.inWorld) PacketOut.equipment(viewer, body.id, EQUIPMENT_SLOTS[slot], item);
    }

    private void visibleBody(Rec.Visible visible, boolean send) {
        Body body = bodies.get(visible.actor());
        if (body == null) return;
        body.visible = visible.visible();
        if (!send) return;
        if (!body.visible && body.inWorld) hideBody(body);
        else if (body.visible && !body.inWorld && body.positioned) showBody(body);
    }

    private void placeBlock(Rec.Block block) {
        BlockData data = palette.get(block.palette());
        if (data == null) return;
        int x = blockX + block.x();
        int y = blockY + block.y();
        int z = blockZ + block.z();
        viewer.sendBlockChange(new Location(world, x, y, z), data);
        changed.add(pack(block.x(), block.y(), block.z()));
    }

    private void spawnProp(Rec.EntitySpawn spawn, boolean send) {
        EntityType type = EntityTypes.getByName(spawn.entityType());
        if (type == null) return;
        Prop prop = new Prop(FakeIds.next(), type, item(spawn.item()));
        prop.x = spawn.x();
        prop.y = spawn.y();
        prop.z = spawn.z();
        prop.yaw = ReplayFormat.angle(spawn.yaw());
        prop.pitch = ReplayFormat.angle(spawn.pitch());
        props.put(spawn.id(), prop);
        if (send) spawnPropPackets(prop);
    }

    private void moveProp(Rec.EntityMove move, boolean send) {
        Prop prop = props.get(move.id());
        if (prop == null) return;
        prop.x = move.x();
        prop.y = move.y();
        prop.z = move.z();
        prop.yaw = ReplayFormat.angle(move.yaw());
        prop.pitch = ReplayFormat.angle(move.pitch());
        if (send && prop.inWorld) PacketOut.move(viewer, prop.id, absX(prop.x), absY(prop.y), absZ(prop.z), prop.yaw, prop.pitch);
    }

    private void removeProp(Rec.EntityRemove remove) {
        Prop prop = props.remove(remove.id());
        if (prop != null && prop.inWorld) {
            PacketOut.destroy(viewer, prop.id);
            prop.inWorld = false;
        }
    }

    private void playEffect(Rec.Effect effect, boolean send) {
        if (!send) return;
        Location at = new Location(world, absX(effect.x()), absY(effect.y()), absZ(effect.z()));
        if (effect.kind() == 0) {
            viewer.spawnParticle(Particle.EFFECT, at, SPLASH_PARTICLES,
                    new Particle.Spell(Color.fromRGB(effect.rgb()), 1f));
        } else {
            viewer.spawnParticle(Particle.EXPLOSION, at, 1);
            viewer.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
        }
    }

    private void showBody(Body body) {
        PacketOut.spawnPlayer(viewer, body.id, body.uuid, body.name, body.skinValue, body.skinSignature,
                absX(body.x), absY(body.y), absZ(body.z), body.yaw, body.pitch);
        PacketOut.headRotation(viewer, body.id, body.headYaw);
        PacketOut.state(viewer, body.id, body.flags, body.hand, body.pose);
        for (int slot = 0; slot < SLOTS; slot++) {
            PacketOut.equipment(viewer, body.id, EQUIPMENT_SLOTS[slot], body.equipment[slot]);
        }
        body.inWorld = true;
    }

    private void hideBody(Body body) {
        PacketOut.destroy(viewer, body.id);
        body.inWorld = false;
    }

    private void spawnPropPackets(Prop prop) {
        PacketOut.spawnEntity(viewer, prop.id, prop.uuid, prop.type, absX(prop.x), absY(prop.y), absZ(prop.z),
                prop.yaw, prop.pitch, prop.item);
        prop.inWorld = true;
    }

    /** Back to the start: sends the real blocks back and forgets every body and prop. */
    private void rewind() {
        restoreBlocks();
        hideEntities();
        bodies.clear();
        props.clear();
        cursor = 0;
        recordTick = 0;
        followIndex = -1;
        followed = null;
        roundBannerUntil = -1;
    }

    private void respawnAll() {
        for (Body body : bodies.values()) {
            if (body.visible && body.positioned) showBody(body);
        }
        for (Prop prop : props.values()) spawnPropPackets(prop);
    }

    private void hideEntities() {
        UUID[] uuids = new UUID[bodies.size()];
        int count = 0;
        for (Body body : bodies.values()) {
            uuids[count++] = body.uuid;
            if (body.inWorld) hideBody(body);
        }
        for (Prop prop : props.values()) {
            if (prop.inWorld) {
                PacketOut.destroy(viewer, prop.id);
                prop.inWorld = false;
            }
        }
        if (count > 0) PacketOut.removeInfo(viewer, uuids);
    }

    private void restoreBlocks() {
        for (long packed : changed) {
            int x = blockX + (short) (packed >>> 32);
            int y = blockY + (short) (packed >>> 16);
            int z = blockZ + (short) packed;
            viewer.sendBlockChange(new Location(world, x, y, z), world.getBlockAt(x, y, z).getBlockData());
        }
        changed.clear();
    }

    private void follow() {
        List<Body> visible = new ArrayList<>();
        for (Body body : bodies.values()) {
            if (body.inWorld) visible.add(body);
        }
        if (visible.isEmpty()) return;
        followIndex = (followIndex + 1) % visible.size();
        Body target = visible.get(followIndex);
        followed = target;
        viewer.teleport(new Location(world, absX(target.x), absY(target.y), absZ(target.z), target.yaw, target.pitch));
    }

    private Saved save() {
        ItemStack[] contents = viewer.getInventory().getContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return new Saved(viewer.getLocation(), viewer.getGameMode(), copy, viewer.getAllowFlight(), viewer.isFlying(),
                viewer.isInvulnerable(), viewer.getFireTicks(), viewer.getHealth(), viewer.getFoodLevel());
    }

    private void restoreViewer(boolean teleportBack) {
        viewer.getInventory().setContents(saved.contents());
        viewer.setInvulnerable(saved.invulnerable());
        viewer.setGameMode(saved.mode());
        viewer.setAllowFlight(saved.allowFlight());
        viewer.setFlying(saved.flying() && saved.allowFlight());
        viewer.setFireTicks(saved.fireTicks());
        viewer.setFoodLevel(saved.food());
        viewer.setHealth(Math.min(saved.health(), viewer.getAttribute(Attribute.MAX_HEALTH).getValue()));
        if (teleportBack) viewer.teleport(saved.location());
    }

    private double absX(double x) {
        return originX + x;
    }

    private double absY(double y) {
        return originY + y;
    }

    private double absZ(double z) {
        return originZ + z;
    }

    /** Recorded flags (own bit layout) to the vanilla entity flags. */
    private static byte vanillaFlags(int recorded) {
        int flags = 0;
        if ((recorded & 1) != 0) flags |= 0x02;
        if ((recorded & 2) != 0) flags |= 0x08;
        if ((recorded & 4) != 0) flags |= 0x10;
        if ((recorded & 8) != 0) flags |= 0x80;
        if ((recorded & 64) != 0) flags |= 0x01;
        if ((recorded & 128) != 0) flags |= 0x20;
        return (byte) flags;
    }

    /** Living hand state: 0x01 hand active, 0x02 off hand. */
    private static byte handFlags(int recorded) {
        int hand = 0;
        if ((recorded & 16) != 0) hand |= 0x01;
        if ((recorded & 32) != 0) hand |= 0x02;
        return (byte) hand;
    }

    /** Bukkit pose by ordinal, as the recorder wrote it; unknown poses stand. */
    private static EntityPose poseOf(int ordinal) {
        if (ordinal < 0 || ordinal >= POSES.length) return EntityPose.STANDING;
        String name = POSES[ordinal].name();
        if ("SNEAKING".equals(name)) return EntityPose.CROUCHING;
        try {
            return EntityPose.valueOf(name);
        } catch (IllegalArgumentException e) {
            return EntityPose.STANDING;
        }
    }

    private static ItemStack item(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? null : ItemStack.deserializeBytes(bytes);
    }

    /** Null for a palette entry this server version cannot parse; its blocks are then skipped. */
    private static BlockData blockData(String data) {
        try {
            return Bukkit.createBlockData(data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFF);
    }
}
