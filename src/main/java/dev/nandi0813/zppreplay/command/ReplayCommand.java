package dev.nandi0813.zppreplay.command;

import dev.nandi0813.zppreplay.ZPPReplay;
import dev.nandi0813.zppreplay.config.Messages;
import dev.nandi0813.zppreplay.menu.ReplayMenu;
import dev.nandi0813.zppreplay.play.PlaybackManager;
import dev.nandi0813.zppreplay.play.ViewerSession;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /replay}: the menu of own replays, {@code play <id>}, {@code leave}, {@code list <player>} and
 * {@code noclip}.
 */
public final class ReplayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("play", "leave", "list", "noclip");

    private final ZPPReplay plugin;
    private final PlaybackManager playback;
    private final ReplayStore store;

    public ReplayCommand(ZPPReplay plugin, PlaybackManager playback, ReplayStore store) {
        this.plugin = plugin;
        this.playback = playback;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.getMessages();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "REPLAY.NO-PERMISSION");
            return true;
        }
        if (args.length == 0) {
            open(player, store.forPlayer(player.getUniqueId()));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "play" -> {
                if (args.length == 2) playback.play(player, args[1]);
                else messages.send(player, "REPLAY.USAGE");
            }
            case "leave" -> playback.leave(player);
            case "list" -> {
                if (args.length == 2) list(player, args[1]);
                else messages.send(player, "REPLAY.USAGE");
            }
            case "noclip" -> noclip(player);
            default -> messages.send(player, "REPLAY.USAGE");
        }
        return true;
    }

    private void noclip(Player player) {
        ViewerSession session = playback.sessionOf(player.getUniqueId());
        if (session == null) plugin.getMessages().send(player, "REPLAY.NOT-WATCHING");
        else session.toggleNoclip();
    }

    private void list(Player player, String name) {
        Messages messages = plugin.getMessages();
        if (!player.hasPermission(PlaybackManager.WATCH_OTHERS_PERMISSION)) {
            messages.send(player, "REPLAY.NO-PERMISSION");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        open(player, target == null ? List.of() : store.forPlayer(target.getUniqueId()));
    }

    private void open(Player player, List<ReplayStore.Entry> entries) {
        if (entries.isEmpty()) {
            plugin.getMessages().send(player, "REPLAY.NO-REPLAYS");
            return;
        }
        new ReplayMenu(plugin, playback, entries).open(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        if (args.length != 2) return List.of();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("play")) {
            return filter(store.forPlayer(player.getUniqueId()).stream().map(ReplayStore.Entry::id).toList(), args[1]);
        }
        if (sub.equals("list") && player.hasPermission(PlaybackManager.WATCH_OTHERS_PERMISSION)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
