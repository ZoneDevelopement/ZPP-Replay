package dev.nandi0813.zppreplay.play;

import dev.nandi0813.api.Enum.PlayerState;
import dev.nandi0813.api.Event.Match.MatchStartEvent;
import dev.nandi0813.api.Interface.ArenaLease;
import dev.nandi0813.api.ZonePracticeApi;
import dev.nandi0813.zppreplay.ZPPReplay;
import dev.nandi0813.zppreplay.config.Messages;
import dev.nandi0813.zppreplay.config.ReplaySettings;
import dev.nandi0813.zppreplay.format.ReplayReader;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Viewer sessions by viewer. Loads replays off the main thread and starts them on it. Main thread only, except loading. */
public final class PlaybackManager implements Listener {

    public static final String WATCH_PERMISSION = "zppreplay.watch";
    public static final String WATCH_OTHERS_PERMISSION = "zppreplay.watch.others";

    private final ZPPReplay plugin;
    private final ReplaySettings settings;
    private final ReplayStore store;
    private final Controls controls;
    private final Map<UUID, ViewerSession> sessions = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();

    public PlaybackManager(ZPPReplay plugin, ReplaySettings settings, ReplayStore store) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.controls = new Controls(plugin, this::sessionOf);
    }

    public Controls controls() {
        return controls;
    }

    /** Registers the listeners and the control items' listener. */
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(controls, plugin);
    }

    /**
     * Starts a replay for the viewer. Checks run in order: already watching, state, unknown id, permission,
     * capacity. The file is read off the main thread; the session starts on the main thread after the checks again.
     */
    public void play(Player viewer, String id) {
        Messages messages = plugin.getMessages();
        UUID uuid = viewer.getUniqueId();
        if (sessions.containsKey(uuid) || loading.contains(uuid)) {
            messages.send(viewer, "REPLAY.ALREADY-WATCHING");
            return;
        }
        if (!isLobby(viewer)) {
            messages.send(viewer, "REPLAY.BUSY");
            return;
        }
        ReplayStore.Entry entry = store.get(id);
        if (entry == null) {
            messages.send(viewer, "REPLAY.NOT-FOUND");
            return;
        }
        boolean participant = entry.participants().contains(uuid);
        if (!(participant && viewer.hasPermission(WATCH_PERMISSION)) && !viewer.hasPermission(WATCH_OTHERS_PERMISSION)) {
            messages.send(viewer, "REPLAY.NO-PERMISSION");
            return;
        }
        if (sessions.size() + loading.size() >= settings.maxConcurrentViewers()) {
            messages.send(viewer, "REPLAY.FULL");
            return;
        }
        loading.add(uuid);
        messages.send(viewer, "REPLAY.LOADING");
        store.read(id).thenApply(PlaybackManager::parse).whenComplete((replay, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> finishLoad(uuid, id, replay, error)));
    }

    /** Ends the viewer's session and says so. Does nothing when the viewer is not watching. */
    public void leave(Player viewer) {
        if (end(viewer.getUniqueId(), true)) plugin.getMessages().send(viewer, "REPLAY.LEFT");
    }

    /** Ends the session of the viewer; the session is forgotten before it restores the viewer. */
    public boolean end(UUID viewer, boolean teleportBack) {
        ViewerSession session = sessions.remove(viewer);
        if (session == null) return false;
        session.end(teleportBack);
        return true;
    }

    public boolean isWatching(UUID viewer) {
        return sessions.containsKey(viewer);
    }

    /** Ends every session, for plugin disable. */
    public void closeAll() {
        for (UUID viewer : new ArrayList<>(sessions.keySet())) end(viewer, true);
        loading.clear();
    }

    public ViewerSession sessionOf(UUID viewer) {
        return sessions.get(viewer);
    }

    private void finishLoad(UUID uuid, String id, ReplayReader.Replay replay, Throwable error) {
        loading.remove(uuid);
        Player viewer = Bukkit.getPlayer(uuid);
        if (viewer == null) return;
        Messages messages = plugin.getMessages();
        if (error != null || replay == null) {
            plugin.getLogger().warning("Could not load replay " + id + ": " + (error == null ? "no data" : error));
            messages.send(viewer, "REPLAY.LOAD-FAILED");
            return;
        }
        if (!isLobby(viewer)) {
            messages.send(viewer, "REPLAY.BUSY");
            return;
        }
        ArenaLease lease = ZonePracticeApi.getInstance().leaseArena(plugin, replay.header().originalArena());
        if (lease == null) {
            messages.send(viewer, "REPLAY.NO-ARENA");
            return;
        }
        ViewerSession session = new ViewerSession(plugin, this, controls, viewer, lease, replay);
        sessions.put(uuid, session);
        try {
            session.start();
        } catch (RuntimeException e) {
            end(uuid, true);
            plugin.getLogger().warning("Replay " + id + " could not start: " + e);
            messages.send(viewer, "REPLAY.LOAD-FAILED");
            return;
        }
        messages.send(viewer, "REPLAY.STARTED");
    }

    private static ReplayReader.Replay parse(byte[] file) {
        try {
            return ReplayReader.read(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isLobby(Player player) {
        return ZonePracticeApi.getInstance().getPlayerState(player) == PlayerState.LOBBY;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (ViewerSession session : sessions.values()) session.hideFrom(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!sessions.containsKey(player.getUniqueId())) return;
        String command = event.getMessage().substring(1).split(" ", 2)[0];
        if (command.equalsIgnoreCase("replay") || command.equalsIgnoreCase("replays")) return;
        event.setCancelled(true);
        plugin.getMessages().send(player, "REPLAY.COMMAND-BLOCKED");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMatchStart(MatchStartEvent event) {
        for (Player player : event.getMatch().getPlayers()) {
            if (sessions.containsKey(player.getUniqueId())) end(player.getUniqueId(), false);
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        ViewerSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        World to = event.getTo().getWorld();
        if (to != null && !to.equals(session.world())) end(event.getPlayer().getUniqueId(), false);
    }
}
