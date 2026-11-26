package dev.nandi0813.zppreplay.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.nandi0813.api.Enum.MatchType;
import dev.nandi0813.api.Interface.Match;
import dev.nandi0813.zppreplay.format.ReplayReader;
import dev.nandi0813.zppreplay.format.ReplayReader.Rec;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A dead player's body stays hidden until a respawn or a round, unless SHOW-DEAD-SPECTATORS shows it. */
class DeadSpectatorsTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @TempDir Path temp;

    @Test
    void deadSpectatorStaysHiddenWhileNotFlying() throws Exception {
        Recording recording = recording(false);
        recording.tick(10);
        recording.victimDied(PLAYER, 11);
        for (int now = 12; now <= 20; now++) recording.tick(now);

        assertEquals(List.of(false), visibility(recording, 21));
    }

    @Test
    void respawnShowsHiddenBody() throws Exception {
        Recording recording = recording(false);
        recording.tick(10);
        recording.victimDied(PLAYER, 11);
        recording.respawned(PLAYER, 20);

        assertEquals(List.of(false, true), visibility(recording, 21));
    }

    @Test
    void roundStartShowsHiddenBody() throws Exception {
        Recording recording = recording(false);
        recording.tick(10);
        recording.victimDied(PLAYER, 11);
        recording.roundStarted(2, 20);

        assertEquals(List.of(false, true), visibility(recording, 21));
    }

    @Test
    void showDeadSpectatorsWritesNoVisibilityRecord() throws Exception {
        Recording recording = recording(true);
        recording.tick(10);
        recording.victimDied(PLAYER, 11);
        for (int now = 12; now <= 15; now++) recording.tick(now);

        assertEquals(List.of(), visibility(recording, 16));
    }

    private Recording recording(boolean showDeadSpectators) {
        return new Recording(match(player()), 0, 1000, showDeadSpectators, Map.of(PLAYER, 1), null);
    }

    /** Finishes the recording into a temp store and returns the visibility of every Visible record, in order. */
    private List<Boolean> visibility(Recording recording, int now) throws Exception {
        ReplayStore store = new ReplayStore(temp, Logger.getAnonymousLogger(), RecordProfiler.create(Logger.getAnonymousLogger(), true));
        try {
            recording.finish(now, store);
        } finally {
            store.close();
        }
        Path file;
        try (var paths = Files.list(temp.resolve("replays"))) {
            file = paths.filter(path -> path.toString().endsWith(".zrp")).findFirst().orElseThrow();
        }
        return ReplayReader.read(Files.readAllBytes(file)).records().stream()
                .filter(record -> record instanceof Rec.Visible)
                .map(record -> ((Rec.Visible) record).visible())
                .toList();
    }

    /** A standing, non-flying player that is always valid; everything else reads as an empty default. */
    private static Player player() {
        PlayerInventory inventory = proxy(PlayerInventory.class, method -> defaultValue(method.getReturnType()));
        PlayerProfile profile = proxy(PlayerProfile.class, method -> defaultValue(method.getReturnType()));
        return proxy(Player.class, method -> switch (method.getName()) {
            case "getUniqueId" -> PLAYER;
            case "getName" -> "Steve";
            case "getPlayerProfile" -> profile;
            case "getInventory" -> inventory;
            case "getPose" -> Pose.STANDING;
            case "isValid" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Match match(Player player) {
        return proxy(Match.class, method -> switch (method.getName()) {
            case "getArenaBounds" -> new BoundingBox(0, 0, 0, 10, 10, 10);
            case "getMatchId" -> "dead-spectators";
            case "getLadderName" -> "classic";
            case "getMatchType" -> MatchType.DUEL;
            case "getOriginalArenaName", "getArenaName" -> "arena";
            case "getPlayers" -> List.of(player);
            case "getBotBodies", "getSpectators" -> List.of();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == Set.class) return Set.of();
        return null;
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                default -> type.getSimpleName() + " proxy";
            };
            return answer.answer(method);
        }));
    }

    @FunctionalInterface private interface Answer {
        Object answer(Method method);
    }
}
